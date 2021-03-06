;; Freecoin - digital social currency toolkit

;; part of Decentralized Citizen Engagement Technologies (D-CENT)
;; R&D funded by the European Commission (FP7/CAPS 610349)

;; Copyright (C) 2015 Dyne.org foundation
;; Copyright (C) 2015 Thoughtworks, Inc.

;; Sourcecode designed, written and maintained by
;; Denis Roio <jaromil@dyne.org>

;; With contributions by
;; Arjan Scherpenisse <arjan@scherpenisse.net>
;; Amy Welch <awelch@thoughtworks.com>

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

(ns freecoin.blockchain
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [fxc.core :as fxc]
            [freecoin.params :as param]
            [freecoin.db
             [mongo :as mongo]
             [tag :as tag]]
            [freecoin.db.storage :as storage]
            [freecoin.utils :as util]
            [simple-time.core :as time]))

(defprotocol Blockchain
  ;; blockchain identifier
  (label [bk])

  ;; account
  (import-account [bk account-id secret])
  (create-account [bk])

  (get-address [bk account-id])
  (get-balance [bk account-id])

  ;; transactions
  (list-transactions [bk params])
  (get-transaction   [bk account-id txid])
  (make-transaction  [bk from-account-id amount to-account-id params from-account-email])

  ;; tags
  (list-tags         [bk params])
  (tag-details       [bk name params])

  ;; vouchers
  (create-voucher [bk account-id amount expiration secret])
  (redeem-voucher [bk account-id voucher]))

(defrecord voucher
    [_id
     expiration
     sender
     amount
     blockchain
     currency])

(defrecord transaction
    [_id
     emission
     broadcast
     signed
     sender
     amount
     recipient
     blockchain
     currency])

;; this is here just to explore how introspection works in clojure records
;; basically one could just explicit the string "STUB" where this is used
(defn recname
  "Return a string which is the name of the record class, uppercase.
Used to identify the class type."
  [record]
  (-> record
      class
      pr-str
      (str/split #"\.")
      last
      str/upper-case))

;; TODO
(defrecord nxt [server port])

(defn- normalize-transactions [list]
  (reverse
   (sort-by :timestamp
            (map (fn [{:keys [amount] :as transaction}]
                   (assoc transaction :amount (util/long->bigdecimal amount)))
                 list))))

(defn merge-params [params f name updater]
  (if-let [request-value (params name)]
    (merge f (updater request-value))
    f))

(defn add-transaction-list-params [request-params]
  (reduce-kv (partial merge-params request-params)
             {}
             {:to
              (fn [v] {:timestamp {"$lt" v}})
              :from
              (fn [v] {:timestamp {"$gt" v}})
              :account-id
              (fn [v] {"$or" [{:from-id v} {:to-id v}]})
              :tags
              (fn [v] {:tags {"$in" v}})}))

(defn add-tags-list-params [request-params]
  (reduce-kv (partial merge-params request-params)
             {}
             {:account-id
              (fn [v] {"$or" [{:from-id v} {:to-id v}]})}))

;; inherits from Blockchain and implements its methods
(defrecord Stub [stores-m]
  Blockchain
  (label [bk] (keyword (recname bk)))

  (import-account [bk account-id secrets] nil)

  (create-account [bk]
    (let [secret (fxc/generate :url 64)
          uniqueid (fxc/generate :url 128)]
      {:account-id uniqueid
       ;; TODO: establish a unique-id generation algo and cycle of
       ;; life; this is not related to the :email uniqueness
       :account-secret secret}
      ;; TODO: wrap all this with symmetric encryption using fxc secrets
      ))

  (get-address [bk account-id] nil)
  
  (get-balance [bk account-id]
    ;; we use the aggregate function in mongodb, sort of simplified map/reduce
    (let [received-map (first (mongo/aggregate (storage/get-transaction-store stores-m) 
                                                 [{"$match" {:to-id account-id}}
                                                  {"$group" {:_id "$to-id"
                                                             :total {"$sum" "$amount"}}}]))
          sent-map  (first (mongo/aggregate (storage/get-transaction-store stores-m)
                                              [{"$match" {:from-id account-id}}
                                               {"$group" {:_id "$from-id"
                                                          :total {"$sum" "$amount"}}}]))
          received (if received-map (:total received-map) 0)
          sent     (if sent-map (:total sent-map) 0)]
      (util/long->bigdecimal (- received sent))))

  (list-transactions [bk params]
    (log/debug "getting transactions" params)
    (normalize-transactions
     (mongo/query (storage/get-transaction-store stores-m) (add-transaction-list-params params))))

  (get-transaction   [bk account-id txid] nil)

  ;; TODO: get rid of account-ids and replace with wallets
  (make-transaction  [bk from-account-id amount to-account-id params from-account-email]
    (let [timestamp (time/format (if-let [time (:timestamp params)] time (time/now)))
          tags (or (:tags params) #{})
          transaction {:_id (str timestamp "-" from-account-id)
                       :blockchain "STUB"
                       :timestamp timestamp
                       :from-id from-account-id
                       :to-id to-account-id
                       :tags tags
                       :amount (util/bigdecimal->long amount)}]

      ;; TODO: Maybe better to do a batch insert with monger.collection/insert-batch? More efficient for a large amount of inserts
      (doall (map #(tag/create-tag! {:tag-store (:tag-store stores-m) 
                                     :tag %
                                     :created-by from-account-email
                                     :created timestamp})
                  tags))
      ;; TODO: Keep track of accounts to verify validity of from- and
      ;; to- accounts
      (mongo/store! (storage/get-transaction-store stores-m) :_id transaction)
      ))

  (list-tags [bk params]
    (let [by-tag [{:$unwind :$tags}]
          tags-params (apply conj by-tag (if (coll? params)
                                             params
                                             [params]))
          params (into tags-params [{:$group {:_id "$tags"
                                              :count {"$sum" 1}
                                              :amount {"$sum" "$amount"}}}])
          tags (mongo/aggregate (storage/get-transaction-store stores-m)  params)]
      (mapv (fn [{:keys [_id count amount]}]
              (let [tag (tag/fetch (:tag-store stores-m) _id)]
                {:tag   _id
                 :count count
                 :amount (util/long->bigdecimal amount)
                 :created-by (:created-by tag)
                 :created (:created tag)}))
            tags)))

  (tag-details [bk name params]
    (first (filter #(= name (:tag %)) (list-tags bk params))))

  (create-voucher [bk account-id amount expiration secret] nil)

  (redeem-voucher [bk account-id voucher] nil))

(defn new-stub
  "Check that the blockchain is available, then return a record"
  [stores-m]
  (Stub. stores-m))

(defn in-memory-filter [entry params]
  true)

;;; in-memory blockchain for testing
(defrecord InMemoryBlockchain [blockchain-label transactions-atom accounts-atom tags-atom]
  Blockchain
  ;; identifier
  (label [bk] blockchain-label)

  ;; account
  (import-account [bk account-id secret] nil)
  (create-account [bk]
    (let [secret (fxc/generate :url 64)
          uniqueid (fxc/generate :url 128)]
      {:account-id uniqueid
       :account-secret secret}))

  (get-address [bk account-id] nil)
  (get-balance [bk account-id]
    (let [all-transactions (vals @transactions-atom)
          total-withdrawn (->> all-transactions
                               (filter (comp (partial = account-id) :from-account-id))
                               (map :amount)
                               (reduce +))
          total-deposited (->> all-transactions
                               (filter (comp (partial = account-id) :to-account-id))
                               (map :amount)
                               (reduce +))]
      (- total-deposited total-withdrawn)))

  ;; transactions
  (list-transactions [bk params] (do
                                   (log/info "In-memory params:" params)
                                   (let [list (vals @transactions-atom)]
                                     (if (empty? params)
                                       list
                                       [(second list)]))))

  (get-transaction   [bk account-id txid] nil)
  (make-transaction  [bk from-account-id amount to-account-id params from-account-email]
    ;; to make tests possible the timestamp here is generated starting from
    ;; the 1 december 2015 plus a number of days that equals the amount
    (let [now (time/format (time/add-days (time/datetime 2015 12 1) amount))
          tags (or (:tags params) #{})
          transaction {:transaction-id (str now "-" from-account-id)
                       :blockchain "INMEMORYBLOCKCHAIN"
                       :timestamp now
                       :from-id from-account-id
                       :to-id to-account-id
                       :tags tags
                       :amount amount}]

      (doall (map #(swap! tags-atom assoc {:tag %
                                           :created-by from-account-email
                                           :created now})
                  tags))
      
      (swap! transactions-atom assoc (:transaction-id transaction) transaction)
      transaction))
  
  ;; vouchers
  (create-voucher [bk account-id amount expiration secret])
  (redeem-voucher [bk account-id voucher]))

(defn create-in-memory-blockchain
  ([label] (create-in-memory-blockchain label (atom {}) (atom {}) (atom {})))

  ([label transactions-atom accounts-atom tags-atom]
   (InMemoryBlockchain. label transactions-atom accounts-atom tags-atom)))
