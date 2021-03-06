(ns datahike.core
  (:refer-clojure :exclude [filter])
  (:require
    [datahike.db :as db #?@(:cljs [:refer [FilteredDB]])]
    [datahike.pull-api :as dp]
    [datahike.query :as dq]
    [datahike.impl.entity :as de]
    [datahike.btset :as btset])
  #?(:clj
    (:import
      [datahike.db FilteredDB]
      [datahike.impl.entity Entity]
      [java.util UUID])))

;; SUMMING UP

(defn ^:declared q [q & inputs])
(def             q dq/q)

(defn ^:declared entity [db eid])
(def             entity de/entity)

(defn entity-db [^Entity entity]
  {:pre [(de/entity? entity)]}
  (.-db entity))

(defn ^:declared datom ([e a v]) ([e a v tx]) ([e a v tx added]))
(def             datom db/datom)

(defn ^:declared pull [db selector eid])
(def             pull dp/pull)

(defn ^:declared pull-many [db selector eids])
(def             pull-many dp/pull-many)

(defn ^:declared touch [e])
(def             touch de/touch)

(defn ^:declared empty-db ([]) ([schema]))
(def             empty-db db/empty-db)

(defn ^:declared init-db ([datoms]) ([datoms schema]))
(def             init-db db/init-db)

(defn ^:declared datom? [x])
(def             datom? db/datom?)

(defn ^:declared db? [x])
(def             db? db/db?)

(def ^:const tx0 db/tx0)

(defn is-filtered [x]
  (instance? FilteredDB x))

(defn filter [db pred]
  {:pre [(db/db? db)]}
  (if (is-filtered db)
    (let [^FilteredDB fdb db
          orig-pred (.-pred fdb)
          orig-db   (.-unfiltered-db fdb)]
      (FilteredDB. orig-db #(and (orig-pred %) (pred orig-db %)) (atom 0)))
    (FilteredDB. db #(pred db %) (atom 0))))

(defn with
  ([db tx-data] (with db tx-data nil))
  ([db tx-data tx-meta]
    {:pre [(db/db? db)]}
    (if (is-filtered db)
      (throw (ex-info "Filtered DB cannot be modified" {:error :transaction/filtered}))
      (db/transact-tx-data (db/map->TxReport
                             { :db-before db
                               :db-after  db
                               :tx-data   []
                               :tempids   {}
                               :tx-meta   tx-meta}) tx-data))))

(defn db-with
  ([db tx-data] (db-with db tx-data nil))
  ([db tx-data tx-meta]
   {:pre [(db/db? db)]}
   (:db-after (with db tx-data tx-meta))))

(defn datoms
  ([db index]             {:pre [(db/db? db)]} (db/-datoms db index []))
  ([db index c1]          {:pre [(db/db? db)]} (db/-datoms db index [c1]))
  ([db index c1 c2]       {:pre [(db/db? db)]} (db/-datoms db index [c1 c2]))
  ([db index c1 c2 c3]    {:pre [(db/db? db)]} (db/-datoms db index [c1 c2 c3]))
  ([db index c1 c2 c3 c4] {:pre [(db/db? db)]} (db/-datoms db index [c1 c2 c3 c4])))

(defn seek-datoms
  ([db index]             {:pre [(db/db? db)]} (db/-seek-datoms db index []))
  ([db index c1]          {:pre [(db/db? db)]} (db/-seek-datoms db index [c1]))
  ([db index c1 c2]       {:pre [(db/db? db)]} (db/-seek-datoms db index [c1 c2]))
  ([db index c1 c2 c3]    {:pre [(db/db? db)]} (db/-seek-datoms db index [c1 c2 c3]))
  ([db index c1 c2 c3 c4] {:pre [(db/db? db)]} (db/-seek-datoms db index [c1 c2 c3 c4])))

(defn index-range [db attr start end]
  {:pre [(db/db? db)]}
  (db/-index-range db attr start end))

(defn ^:declared entid [db eid])
(def             entid db/entid)

;; Conn

(defn conn? [conn]
  (and #?(:clj  (instance? clojure.lang.IDeref conn)
          :cljs (satisfies? cljs.core/IDeref conn))
    (db/db? @conn)))

(defn conn-from-db [db]
  (atom db :meta { :listeners (atom {}) }))

(defn conn-from-datoms
  ([datoms]        (conn-from-db (init-db datoms)))
  ([datoms schema] (conn-from-db (init-db datoms schema))))

(defn create-conn
  ([]       (conn-from-db (empty-db)))
  ([schema] (conn-from-db (empty-db schema))))

(defn -transact! [conn tx-data tx-meta]
  {:pre [(conn? conn)]}
  (let [report (atom nil)]
    (swap! conn (fn [db]
                  (let [r (with db tx-data tx-meta)]
                    (reset! report r)
                    (:db-after r))))
    @report))

(defn transact!
  ([conn tx-data] (transact! conn tx-data nil))
  ([conn tx-data tx-meta]
    {:pre [(conn? conn)]}
    (let [report (-transact! conn tx-data tx-meta)]
      (doseq [[_ callback] @(:listeners (meta conn))]
        (callback report))
      report)))

(defn reset-conn!
  ([conn db] (reset-conn! conn db nil))
  ([conn db tx-meta]
    (let [report (db/map->TxReport
                  { :db-before @conn
                    :db-after  db
                    :tx-data   (concat
                                 (map #(assoc % :added false) (datoms @conn :eavt))
                                 (datoms db :eavt))
                    :tx-meta   tx-meta})]
      (reset! conn db)
      (doseq [[_ callback] @(:listeners (meta conn))]
        (callback report))
      db)))

(defn listen!
  ([conn callback] (listen! conn (rand) callback))
  ([conn key callback]
     {:pre [(conn? conn)]}
     (swap! (:listeners (meta conn)) assoc key callback)
     key))

(defn unlisten! [conn key]
  {:pre [(conn? conn)]}
  (swap! (:listeners (meta conn)) dissoc key))


;; ----------------------------------------------------------------------------
;; define data-readers to be made available to EDN readers. in CLJS
;; they're magically available. in CLJ, data_readers.clj may or may
;; not work, but you can always simply do
;;
;;  (clojure.edn/read-string {:readers datahike/data-readers} "...")
;;

(def data-readers {'datahike/Datom db/datom-from-reader
                   'datahike/DB    db/db-from-reader})

#?(:cljs
   (doseq [[tag cb] data-readers] (cljs.reader/register-tag-parser! tag cb)))


;; Datomic compatibility layer

(def ^:private last-tempid (atom -1000000))

(defn tempid
  ([part]
    (if (= part :db.part/tx)
      :db/current-tx
      (swap! last-tempid dec)))
  ([part x]
    (if (= part :db.part/tx)
      :db/current-tx
      x)))

(defn resolve-tempid [_db tempids tempid]
  (get tempids tempid))

(defn db [conn]
  {:pre [(conn? conn)]}
  @conn)

(defn transact
  ([conn tx-data] (transact conn tx-data nil))
  ([conn tx-data tx-meta]
    {:pre [(conn? conn)]}
    (let [res (transact! conn tx-data tx-meta)]
      #?(:cljs
         (reify
           IDeref
           (-deref [_] res)
           IDerefWithTimeout
           (-deref-with-timeout [_ _ _] res)
           IPending
           (-realized? [_] true))
         :clj
         (reify
           clojure.lang.IDeref
           (deref [_] res)
           clojure.lang.IBlockingDeref
           (deref [_ _ _] res)
           clojure.lang.IPending
           (isRealized [_] true))))))

;; ersatz future without proper blocking
#?(:cljs
   (defn- future-call [f]
     (let [res      (atom nil)
           realized (atom false)]
       (js/setTimeout #(do (reset! res (f)) (reset! realized true)) 0)
       (reify
         IDeref
         (-deref [_] @res)
         IDerefWithTimeout
         (-deref-with-timeout [_ _ timeout-val] (if @realized @res timeout-val))
         IPending
         (-realized? [_] @realized)))))

(defn transact-async
  ([conn tx-data] (transact-async conn tx-data nil))
  ([conn tx-data tx-meta]
    {:pre [(conn? conn)]}
    (future-call #(transact! conn tx-data tx-meta))))

(defn- rand-bits [pow]
  (rand-int (bit-shift-left 1 pow)))

#?(:cljs
  (defn- to-hex-string [n l]
    (let [s (.toString n 16)
          c (count s)]
      (cond
        (> c l) (subs s 0 l)
        (< c l) (str (apply str (repeat (- l c) "0")) s)
        :else   s))))

(defn squuid
  ([]
    (squuid #?(:clj  (System/currentTimeMillis)
               :cljs (.getTime (js/Date.)))))
  ([msec]
  #?(:clj
      (let [uuid     (UUID/randomUUID)
            time     (int (/ msec 1000))
            high     (.getMostSignificantBits uuid)
            low      (.getLeastSignificantBits uuid)
            new-high (bit-or (bit-and high 0x00000000FFFFFFFF)
                             (bit-shift-left time 32)) ]
        (UUID. new-high low))
     :cljs
       (uuid
         (str
               (-> (int (/ msec 1000))
                   (to-hex-string 8))
           "-" (-> (rand-bits 16) (to-hex-string 4))
           "-" (-> (rand-bits 16) (bit-and 0x0FFF) (bit-or 0x4000) (to-hex-string 4))
           "-" (-> (rand-bits 16) (bit-and 0x3FFF) (bit-or 0x8000) (to-hex-string 4))
           "-" (-> (rand-bits 16) (to-hex-string 4))
               (-> (rand-bits 16) (to-hex-string 4))
               (-> (rand-bits 16) (to-hex-string 4)))))))

(defn squuid-time-millis [uuid]
  #?(:clj (-> (.getMostSignificantBits ^UUID uuid)
              (bit-shift-right 32)
              (* 1000))
     :cljs (-> (subs (str uuid) 0 8)
               (js/parseInt 16)
               (* 1000))))
