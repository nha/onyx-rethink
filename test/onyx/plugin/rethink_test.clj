(ns onyx.plugin.rethink-test
  (:require
   [clojure.core.async :refer [chan >!! <!! close!]]
   [clojure.test :refer :all]
   [onyx.plugin.rethink :as rethink]
   [onyx.plugin.core-async]
   [onyx.api]
   [rethinkdb.query :as r :refer [connect]])
  (:import [java.util UUID]))

(def uuid #(UUID/randomUUID))

(def id (uuid))

(def env-config
  {:zookeeper/address "127.0.0.1:2188"
   :zookeeper/server? true
   :zookeeper.server/port 2188
   :onyx/id id})

(def peer-config
  {:zookeeper/address "127.0.0.1:2188"
   :onyx.peer/job-scheduler :onyx.job-scheduler/greedy
   :onyx.messaging/impl :aeron
   :onyx.messaging/peer-port-range [40200 40400]
   :onyx.messaging/bind-addr "localhost"
   :onyx/id id})

(defonce dev-env (onyx.api/start-env env-config))
(defonce peer-group (onyx.api/start-peer-group peer-config))

(def facts [{:id 1, :msg "foo"}
            {:id 2, :msg "bar"}
            {:id 3, :msg "baz"}
            :done])

(def workflow
  [[:in :rethink]])

(defn catalog [db-name tbl-name]
  [{:onyx/name :in
    :onyx/plugin :onyx.plugin.core-async/input
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/batch-size 1000
    :onyx/max-peers 1
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :rethink
    :onyx/plugin :onyx.plugin.rethink/write-documents
    :onyx/type :output
    :onyx/medium :rethink
    :rethink/db db-name
    :rethink/table tbl-name
    :onyx/batch-size 1000
    :onyx/doc "Writes segments from the :rows keys to the named RethinkDB table"}])

(def in-chan (chan 1000))

(doseq [fact facts]
  (>!! in-chan fact))

(defn inject-in-ch [event lifecycle]
  {:core.async/chan in-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.rethink-test/in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}])

(defn test-name [len]
  (let [chars (map char
                   (concat (range 48 58)
                           (range 66 91)
                           (range 97 123)))]
    (reduce str (take len (repeatedly #(rand-nth chars))))))

(defn create-test-db [db-name table-name]
  (with-open [db (connect)]
    (-> (r/db-create db-name)
        (r/run db))

    (-> (r/db db-name)
        (r/table-create table-name)
        (r/run db))))

(defn destroy-test-db [db-name]
  (with-open [db (connect)]
    (-> (r/db-drop db-name)
        (r/run db))))

(defn destroy-all-dbs []
  (with-open [db (connect)]
    (let [dbs (r/run (r/db-list) db)]
      (doseq [d dbs]
        (when-not (= d "rethinkdb")
          (r/run (r/db-drop d) db))))))

(deftest test-write-docs
  (let [db-name (test-name 10)
        tbl-name (test-name 10)]
    (create-test-db db-name tbl-name)

    (let [v-peers (onyx.api/start-peers 10 peer-group)
          job (onyx.api/submit-job peer-config
                     {:catalog (catalog db-name tbl-name)
                      :workflow workflow
                      :lifecycles lifecycles
                      :task-scheduler :onyx.task-scheduler/balanced})]

      (onyx.api/await-job-completion peer-config (:job-id job))

      (is (= (set [{:id 1, :msg "foo"} {:id 2, :msg "bar"} {:id 3, :msg "baz"}])
             (set
              (with-open [db (connect)]
                (-> (r/db db-name)
                    (r/table tbl-name)
                    (r/run db)))))))))
