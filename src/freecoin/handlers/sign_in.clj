;; Freecoin - digital social currency toolkit

;; part of Decentralized Citizen Engagement Technologies (D-CENT)
;; R&D funded by the European Commission (FP7/CAPS 610349)

;; Copyright (C) 2015 Dyne.org foundation
;; Copyright (C) 2015 Thoughtworks, Inc.

;; Sourcecode designed, written and maintained by
;; Denis Roio <jaromil@dyne.org>
;; Aspasia Beneti <aspra@dyne.org>

;; With contributions by
;; Duncan Mortimer <dmortime@thoughtworks.com>

;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.

;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns freecoin.handlers.sign-in
  (:require [liberator.core :as lc]
            [liberator.representation :as lr]
            [ring.util.response :as r]
            [clojure.string :as s]
            [stonecutter-oauth.client :as soc]
            [freecoin.routes :as routes]
            [freecoin.db.wallet :as wallet]
            [freecoin.db.account :as account]
            [freecoin.db.mongo :as mongo]
            [freecoin.auth :as auth]
            [freecoin.views :as fv]
            [freecoin.views.landing-page :as landing-page]
            [freecoin.views.index-page :as index-page]
            [freecoin.views.sign-in :as sign-in-page]
            [freecoin.views.email-confirmation :as email-confirmation]
            [freecoin.views.account-activated :as aa]
            [freecoin.form_helpers :as fh]
            [freecoin.context-helpers :as ch]
            [taoensso.timbre :as log]
            [postal.core :as postal]))

(lc/defresource index-page
  :allowed-methods [:get]
  :available-media-types ["text/html"]
  :handle-ok (-> (index-page/build)
                 fv/render-page))

(lc/defresource landing-page [wallet-store]
  :allowed-methods [:get]
  :available-media-types ["text/html"]

  :exists? (fn [ctx]
             (if-let [email (:email (auth/is-signed-in ctx))]
               (let [wallet (wallet/fetch wallet-store email)]
                 {:wallet wallet})
               {}))

  :handle-ok (fn [ctx]
               (if-let [wallet (:wallet ctx)]
                 (-> (routes/absolute-path :account :email (:email wallet))
                     r/redirect
                     lr/ring-response)
                 (-> {:sign-in-url "/sign-in"}
                     landing-page/landing-page
                     fv/render-page))))

(lc/defresource sign-in 
  :allowed-methods [:get]
  :available-media-types ["text/html"]
  :handle-ok (fn [ctx] 
               (-> ctx 
                   sign-in-page/build
                   fv/render-page)))

(lc/defresource log-in [account-store wallet-store blockchain]
  :allowed-methods [:post]
  :available-media-types ["text/html"]

  :authorized? (fn [ctx]
                 (let [{:keys [status data problems]}
                       (fh/validate-form sign-in-page/sign-in-form
                                         (ch/context->params ctx))]
                   (if (= :ok status)
                     (let [email (-> ctx :request :params :sign-in-email)]
                       (if-let [account (account/fetch account-store email)]
                         ;; TODO passrd encr
                         (if (= (-> ctx :request :params :sign-in-password) (:password account))
                           {:email email}
                           [false (fh/form-problem (conj problems
                                                          {:keys [:sign-in-password] :msg (str "Wrong password for account " email)}))])
                         [false (fh/form-problem (conj problems
                                                        {:keys [:sign-in-email] :msg "Account with for this email does not exist"}))]))
                     (do
                       (log/info (str "Problems: " (clojure.pprint/pprint problems)))
                       [false (fh/form-problem problems)]))))

  :handle-unauthorized (fn [ctx]
                         (lr/ring-response (fh/flash-form-problem
                                            (r/redirect (routes/absolute-path :sign-in-form))
                                            ctx)))

  :post! (fn [ctx]
           ;; the wallet exists already
           (let [email (:email ctx)
                 name (first (s/split email #"@"))]
             (if-let [wallet (wallet/fetch wallet-store email)]
               (do
                 (log/info "The wallet for email " email " already exists")
                 {::email (:email wallet)})
               
               ;; a new wallet has to be made
               (when-let [{:keys [wallet apikey]}
                          (wallet/new-empty-wallet!
                              wallet-store
                            blockchain 
                            name email)]

                 ;; TODO: distribute other shares to organization and auditor
                 ;; see in freecoin.db.wallet
                 ;; {:wallet (mongo/store! wallet-store :uid wallet)
                 ;;  :apikey       (secret->apikey              account-secret)
                 ;;  :participant  (secret->participant-shares  account-secret)
                 ;;  :organization (secret->organization-shares account-secret)
                 ;;  :auditor      (secret->auditor-shares      account-secret)
                 ;;  }))

                 ;; saved in context
                 {::email (:email wallet)}))))

  :handle-created (fn [ctx]
                   (log/info "CREATED")
                   (lr/ring-response
                    (cond-> (r/redirect (routes/absolute-path :account :email (::email ctx)))
                      (::cookie-data ctx) (assoc-in [:session :cookie-data] (::cookie-data ctx))
                      true (assoc-in [:session :signed-in-email] (::email ctx))))))


(defn check-content-type [ctx content-types]
  (if (#{:put :post} (get-in ctx [:request :request-method]))
    (or
     (some #{(get-in ctx [:request :headers "content-type"])}
           content-types)
     [false {:message "Unsupported Content-Type"}])
    true))


(def content-types ["text/html" "application/x-www-form-urlencoded"])

(defn- generate-activation-id []
  (fxc.core/generate 32))

(defn- activation-link [activationid]
  (routes/absolute-path :activate-account :activation-id activationid))

(defn- send-email-message [email activation-link]
  ;; TODO what to do with file, env?
  (let [conf (clojure.edn/read-string (slurp "email-conf.edn"))]
    ;; TODO with environ
    (postal/send-message 
     {:host (:freecoin-email-server conf)
      :user (:freecoin-email-user conf)
      :pass (:freecoin-email-pass conf)
      :ssl true}
     {:from (:freecoin-email-address conf)
      :to [email]
      :subject "Please activate your freecoin account"
      :body (str "Please click to activate your account " activation-link)})))

(defn email-activation-link [ctx account-store email]
  (let [activation-id (generate-activation-id)
        activation-url (activation-link activation-id)]
    (account/update-activation-id! account-store email activation-id)
    (let [email-response (send-email-message email activation-url)]
      (when-not (= :SUCCESS (:error email-response))
        (-> ctx
            (assoc :error "The activation email failed to send ")
            (routes/absolute-path :landing-page)
            r/redirect
            lr/ring-response)))))

(lc/defresource create-account [account-store]
  :allowed-methods [:post]
  :available-media-types content-types

  :known-content-type? #(check-content-type % content-types)

  :processable? (fn [ctx]
                  (log/info "Processable")
                  (let [{:keys [status data problems]}
                        (fh/validate-form sign-in-page/sign-up-form
                                          (ch/context->params ctx))]
                    (if (= :ok status)
                      (let [email (-> ctx :request :params :email)]
                        (if (account/fetch account-store email)
                          [false (fh/form-problem (conj problems
                                                        {:keys [:password] :msg (str "An account with email " email
                                                                                     " already exists.")}))]
                          ctx))
                      [false (fh/form-problem problems)])))

  :handle-unprocessable-entity (fn [ctx]
                                 (log/info "unprocessable")
                                 (lr/ring-response (fh/flash-form-problem
                                                    (r/redirect (routes/absolute-path :sign-in))
                                                    ctx)))
  :post! (fn [ctx]
           (log/info "post!")
           (let [data (-> ctx :request :params)
                 email (get data :email)]
             ;; TODO add actions if db or email failed
             (account/new-account! account-store (select-keys data [:first-name :last-name :email :password]))
             (email-activation-link ctx account-store email)
             ;; TODO SEND EMAIL HERE
))


  ;; TODO: this should be replaced with a confirmation page, while waiting for the email confirmation
  :post-redirect? (fn [ctx]
                    (log/info "post-redirect")                    
                    (assoc ctx
                           :location (routes/absolute-path :email-confirmation)
                           :email (:email ctx))))

(lc/defresource email-confirmation
  :allowed-methods [:get]
  :available-media-types ["text/html"]
  :handle-ok (fn [ctx]
               (log/info "Email confirmation ")
               (-> ctx
                   (email-confirmation/build)
                   fv/render-page)))

(lc/defresource activate-account [account-store]
  :allowed-methods [:get]
  :available-media-types ["text/html"]
  :handle-ok (fn [ctx]
               (let [activation-code (get-in ctx [:request :params :activation-id])]
                 (if-let [account (account/fetch-by-activation-id account-store activation-code)]
                   ;; TODO is this the right way or should the email be provided?
                   (do (account/activate! account-store (:email account))
                       (-> (routes/absolute-path :account-activated)
                           r/redirect 
                           lr/ring-response))
                   (-> ctx (assoc :error "The activation id could not be found")
                       (routes/absolute-path :landing-page)
                       r/redirect
                       lr/ring-response)))))

(lc/defresource account-acivated
  :allowed-methods [:get]
  :available-media-types ["text/html"]
  :handle-ok (fn [ctx]
               (log/info "Account activated ")
               (-> ctx
                   (aa/build)
                   fv/render-page)))

(defn preserve-session [response request]
  (assoc response :session (:session request)))

(lc/defresource sign-out
  :allowed-methods [:get]
  :available-media-types ["text/html"]

  :handle-ok (fn [ctx]
               (-> (routes/absolute-path :index)
                   r/redirect
                   (preserve-session (:request ctx))
                   (update-in [:session] dissoc :signed-in-email)
                   lr/ring-response)))
