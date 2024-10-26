(ns metabase.channel.impl.email
  (:require
   [metabase.channel.core :as channel]
   [metabase.channel.params :as channel.params]
   [metabase.channel.shared :as channel.shared]
   [metabase.email :as email]
   [metabase.email.messages :as messages]
   [metabase.models.channel :as models.channel]
   [metabase.models.notification :as models.notification]
   [metabase.util :as u]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [stencil.core :as stencil]))

(def ^:private EmailMessage
  [:map
   [:subject                         :string]
   [:recipients                      [:sequential ms/Email]]
   [:message-type                    [:enum :attachments :html :text]]
   [:message                         :any]
   [:recipient-type {:optional true} [:maybe (ms/enum-keywords-and-strings :cc :bcc)]]])

(defn- construct-email
  ([subject recipients message]
   (construct-email subject recipients message nil))
  ([subject recipients message recipient-type]
   {:subject        subject
    :recipients     recipients
    :message-type   :attachments
    :message        message
    :recipient-type recipient-type}))

(defn- recipients->emails
  [recipients]
  (update-vals
   {:user-emails     (mapv (comp :email :user) (filter #(= :user (:kind %)) recipients))
    :non-user-emails (mapv :email (filter #(= :external-email (:kind %)) recipients))}
   #(filter u/email? %)))

;; ------------------------------------------------------------------------------------------------;;
;;                                           Alerts                                                ;;
;; ------------------------------------------------------------------------------------------------;;

(defn- find-goal-value
  "The goal value can come from a progress goal or a graph goal_value depending on it's type"
  [card]
  (case (:display card)

    (:area :bar :line)
    (get-in card [:visualization_settings :graph.goal_value])

    :progress
    (get-in card [:visualization_settings :progress.goal])

    nil))

(mu/defmethod channel/send! :channel/email
  [_channel {:keys [subject recipients message-type message recipient-type]} :- EmailMessage]
  (email/send-message-or-throw! {:subject      subject
                                 :recipients   recipients
                                 :message-type message-type
                                 :message      message
                                 :bcc?         (if recipient-type
                                                 (= :bcc recipient-type)
                                                 (email/bcc-enabled?))}))

(mu/defmethod channel/render-notification [:channel/email :notification/alert] :- [:sequential EmailMessage]
  [_channel-type {:keys [card pulse payload pulse-channel]} _template recipients]
  (let [condition-kwd             (messages/pulse->alert-condition-kwd pulse)
        email-subject             (case condition-kwd
                                    :meets (trs "Alert: {0} has reached its goal" (:name card))
                                    :below (trs "Alert: {0} has gone below its goal" (:name card))
                                    :rows  (trs "Alert: {0} has results" (:name card)))
        {:keys [user-emails
                non-user-emails]} (recipients->emails recipients)
        timezone                  (channel.shared/defaulted-timezone card)
        goal                      (find-goal-value card)
        email-to-users            (when (> (count user-emails) 0)
                                    (construct-email
                                     email-subject user-emails
                                     (messages/render-alert-email timezone pulse pulse-channel
                                                                  [payload]
                                                                  goal
                                                                  nil)))
        email-to-nonusers         (for [non-user-email non-user-emails]
                                    (construct-email
                                     email-subject [non-user-email]
                                     (messages/render-alert-email timezone pulse pulse-channel
                                                                  [payload]
                                                                  goal
                                                                  non-user-email)))]
    (filter some? (conj email-to-nonusers email-to-users))))

;; ------------------------------------------------------------------------------------------------;;
;;                                    Dashboard Subscriptions                                      ;;
;; ------------------------------------------------------------------------------------------------;;

(mu/defmethod channel/render-notification [:channel/email :notification/dashboard-subscription] :- [:sequential EmailMessage]
  [_channel-type {:keys [dashboard payload pulse]} _template recipients]
  (let [{:keys [user-emails
                non-user-emails]} (recipients->emails recipients)
        timezone                  (some->> payload (some :card) channel.shared/defaulted-timezone)
        email-subject             (:name dashboard)
        email-to-users            (when (seq user-emails)
                                    (construct-email
                                     email-subject
                                     user-emails
                                     (messages/render-pulse-email timezone pulse dashboard payload nil)))
        email-to-nonusers         (for [non-user-email non-user-emails]
                                    (construct-email
                                     email-subject
                                     [non-user-email]
                                     (messages/render-pulse-email timezone pulse dashboard payload non-user-email)))]
    (filter some? (conj email-to-nonusers email-to-users))))

;; ------------------------------------------------------------------------------------------------;;
;;                                         System Events                                           ;;
;; ------------------------------------------------------------------------------------------------;;

(defn- notification-recipients->emails
  [recipients notification-payload]
  (into [] cat (for [recipient recipients
                     :let [details (:details recipient)
                           emails (case (:type recipient)
                                    :notification-recipient/user
                                    [(-> recipient :user :email)]
                                    :notification-recipient/group
                                    (->> recipient :permissions_group :members (map :email))
                                    :notification-recipient/external-email
                                    [(:email details)]
                                    :notification-recipient/template
                                    [(not-empty (channel.params/substitute-params (:pattern details) notification-payload :ignore-missing? (:is_optional details)))]
                                    nil)]
                     :let  [emails (filter some? emails)]
                     :when (seq emails)]
                 emails)))

(defn- render-body
  [{:keys [details] :as _template} payload]
  (case (keyword (:type details))
    :email/mustache-resource
    (stencil/render-file (:path details) payload)
    :email/mustache-text
    (stencil/render-string (:body details) payload)
    nil))

(mu/defmethod channel/render-notification
  [:channel/email :notification/system-event]
  [_channel-type
   notification-payload #_:- #_notification/NotificationPayload
   template             :- models.channel/ChannelTemplate
   recipients           :- [:sequential models.notification/NotificationRecipient]]
  (assert (some? template) "Template is required for system event notifications")
  [(construct-email (channel.params/substitute-params (-> template :details :subject) notification-payload)
                    (notification-recipients->emails recipients notification-payload)
                    [{:type    "text/html; charset=utf-8"
                      :content (render-body template notification-payload)}]
                    (-> template :details :recipient-type keyword))])
