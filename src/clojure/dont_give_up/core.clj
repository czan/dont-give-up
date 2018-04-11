(ns dont-give-up.core
  (:import [dont_give_up UseRestart HandlerResult]))

(def ^:dynamic *handlers* [])
(def ^:dynamic *restarts* [])

(defrecord Restart [name describe applicable? make-arguments behaviour])

(defn signal [ex]
  (if (seq *handlers*)
    (let [[handler & others] *handlers*]
      (binding [*handlers* others]
        ;; TODO: running these handlers should probably re-establish
        ;; all of the bindings at the point where the handlers are
        ;; conceptually run (ie. higher up the stack), which would
        ;; then also include the *handlers* var
        (handler ex)))
    (throw ex)))

(defn applicable-restarts [restarts ex]
  (filterv (fn [restart]
             ((:applicable? restart) ex))
           restarts))

(defn use-restart [name & args]
  (let [restart (if (instance? Restart name)
                  name
                  (->> *restarts*
                       (filter #(= (:name %) name))
                       first))]
    (throw (if restart
             (UseRestart. restart args)
             (IllegalArgumentException. (str "No restart registered for " name))))))

(defn handled-value [id value]
  (throw (HandlerResult. id #(do value))))

(defn thrown-value [id value]
  (throw (HandlerResult. id #(throw value))))

(defn with-handler-fn [thunk handler]
  (let [id (gensym "handle-id")]
    (try
      (binding [*handlers* (cons (fn [ex]
                                   (try (binding [*restarts* (applicable-restarts *restarts* ex)]
                                          ;; run the handlers in an
                                          ;; environment hiding the
                                          ;; restarts that aren't
                                          ;; applicable
                                          (handled-value id (handler ex)))
                                        (catch UseRestart t
                                          (throw t))
                                        (catch HandlerResult t
                                          (throw t))
                                        (catch Throwable t
                                          (thrown-value id t))))
                                 *handlers*)]
        (try
          (thunk)
          (catch UseRestart t
            (throw t))
          (catch HandlerResult t
            (throw t))
          (catch Throwable t
            (signal t))))
      (catch HandlerResult t
        (if (= (.-handlerId t) id)
          ((.-thunk t))
          (throw t))))))

(defn with-restarts-fn [thunk restarts]
  (try
    (binding [*restarts* (concat restarts *restarts*)]
      (try
        (thunk)
        (catch UseRestart t
          (throw t))
        (catch HandlerResult t
          (throw t))
        (catch Throwable t
          (signal t))))
    (catch UseRestart t
      (if (some #(= % (.-restart t)) restarts)
        (apply (:behaviour (.-restart t)) (.-args t))
        (throw t)))))

(defn prompt-with-stdin [prompt]
  (print prompt)
  (flush)
  (read-line))

(defn prompt-user [prompt]
  (let [f (or (ns-resolve 'dont-give-up.middleware 'prompt-for-input)
              prompt-with-stdin)]
    (f prompt)))

(defn eval* [form]
  (let [f (or (ns-resolve 'dont-give-up.middleware 'handled-eval)
              eval)]
    (f form)))

(defn read-unevaluated-value [ex]
  [(try (read-string (prompt-user "Enter a value to be used (unevaluated): "))
        (catch Exception _
          (throw ex)))])

(defn read-and-eval-value [ex]
  [(eval* (try (read-string (prompt-user "Enter a value to be used (evaluated): "))
               (catch Exception _
                 (throw ex))))])

(defmacro with-restarts
  {:style/indent [1 [[:defn]] :form]}
  [restarts & body]
  `(with-restarts-fn
     (fn ^:once [] ~@body)
     (lazy-seq
      ~(mapv (fn [restart]
               (if (symbol? restart)
                 restart
                 (let [[name args & body] restart]
                   (loop [body body
                          describe `(constantly "")
                          applicable? `(constantly true)
                          make-arguments `(constantly nil)]
                     (cond
                       (= (first body) :describe)
                       (recur (nnext body)
                              (second body)
                              applicable?
                              make-arguments)

                       (= (first body) :applicable?)
                       (recur (nnext body)
                              describe
                              (second body)
                              make-arguments)

                       (= (first body) :arguments)
                       (recur (nnext body)
                              describe
                              applicable?
                              (second body))

                       :else
                       `(->Restart ~name
                                   (let [d# ~describe]
                                     (if (string? d#)
                                       (constantly d#)
                                       d#))
                                   ~applicable?
                                   ~make-arguments
                                   (fn ~(vec args)
                                     ~@body)))))))
             restarts))))

(defmacro with-handlers
  {:style/indent [1 [[:defn]] :form]}
  [handlers & body]
  (let [ex-sym (gensym "ex")]
    `(with-handler-fn
      (fn ^:once [] ~@body)
      (fn [~ex-sym]
        (cond
          ~@(mapcat (fn [[type arg & body]]
                      `((instance? ~type ~ex-sym)
                        (let [~arg ~ex-sym]
                          ~@body)))
                    handlers)
          :else (signal ~ex-sym))))))
