(ns bifrost.ipmi.state-machine
  (:require
   [automat.viz :refer [view]]
   [automat.core :as a]
   [gloss.io :refer [encode decode]]
   [bifrost.ipmi.application-state :refer :all]
   [bifrost.ipmi.codec :as c]
   [bifrost.ipmi.crypto :refer [calc-sha1-key calc-rakp-1 calc-rakp-3 calc-rakp-4-sidm calc-rakp-4-sik]]
   [bifrost.ipmi.registrar :as r]
   [bifrost.ipmi.utils :refer [safe]]
   [manifold.stream :as s]
   [bifrost.ipmi.handlers :as h]
   [taoensso.timbre :as log]
   [clj-uuid :as uuid]
   [buddy.core.codecs :as codecs]
   [buddy.core.bytes :as bytes]
   [byte-streams :as bs]
   [buddy.core.nonce :as nonce])
  (:import [java.time Duration Instant]))

(declare ipmi-server-fsm)
(declare ipmi-server-handler)
(declare ipmi-client-fsm)
(declare ipmi-client-handler)
(declare mock-handler)
(defonce server-socket (atom nil))

(defn bind-server-fsm []
  (partial a/advance (a/compile ipmi-server-fsm ipmi-server-handler)))

(defn bind-client-fsm []
  (partial a/advance (a/compile ipmi-client-fsm ipmi-client-handler)))

(defn upsert-chan [host-hash chan-map]
  (dosync
   ;(letfn [(add-chan-map
   ;         [ks & opts]
   ;          (let [_ (println ks)
   ;                peer-set (update-in ks [:peer-set] conj (first opts)))
   ;                chan-map (assoc-in peer-set [:chan-map (first opts)] (fnext opts))]
   ;          chan-map)
   (alter app-state update-in [:peer-set] conj host-hash)
   (alter app-state assoc-in [host-hash :chan-map] chan-map)
   ; #_(alter app-state #(add-chan-map % host-hash chan-map))
   ))

(defn delete-chan [host-hash]
  (dosync
   (letfn [(del-chan-map
             [ks & opts]
             (let  [peer-set (update-in ks [:peer-set] #(disj % (first opts)))
                    chan-map (update-in peer-set [:chan-map] dissoc (first opts))]
               chan-map))]
     (alter app-state #(del-chan-map % host-hash)))))

(defn channel-exists? [h]
  (let [peer-set (get-in @app-state [:peer-set])]
    (-> (some #{h} peer-set) boolean)))

(defn get-peers []
  (get-in @app-state [:peer-set] #{}))

(defn count-peer []
  (count (get-in @app-state [:peer-set])))

(defn get-chan-map []
  (get-in @app-state [:chan-map] {}))

(defn update-chan-map-state [h state]
  (dosync
   (alter app-state assoc-in [:chan-map h :state] state)))

(defn get-chan-map-state [h]
  (get-in @app-state [:chan-map h :state] {}))

(defn get-chan-map-host-map [h]
  (get-in @app-state [:chan-map h :host-map] {}))

(defn get-seq-no [m]
  (get-in m [:rmcp-class :ipmi-session-payload
             :ipmi-1-5-payload :ipmb-payload
             :source-lun :seq-no] 0))

(declare reset-peer)

(defn dump-app-state []
  (for [[k v] (get-chan-map)
        :let  [n (.toInstant (java.util.Date.))
               t (.toInstant (:created-at v))
               duration (.toMillis (Duration/between t n))]]
    {:hash k :duration duration}))

;; (add-watch session-atom :watcher
;;            (fn [key atom old-state new-state]
;;              (log/debug "-- Atom Changed -- key" key " atom" atom " old-state" old-state " new-state" new-state)))


;; TODO update for IPV6 sources

(defn create-server-rn! []
  (vec (nonce/random-nonce 16)))

(defn create-server-sid! []
  (rand-int (.pow (BigInteger. "2") 16)))

(defn get-session-state [msg]
  (let [sender (:sender msg)
        address (-> (.getAddress sender) (.getHostAddress))
        port (.getPort sender)]
    {:host address :port port}))

(defn send-udp [session message]
  {:pre (string? (:host session))}
  (let [host  (get session :host)
        port  (get session :port)
        bytes (-> message bs/to-byte-array)]
    (log/info "Sending Message to host:" host " port:" port)
    (log/debug  "Bytes" (-> message
                            bs/to-byte-array
                            codecs/bytes->hex))
    (try
      (s/put! @server-socket {:host    host
                              :port    port
                              :message bytes})
      (catch Exception e
        (throw (ex-info "Exception sending udp"
                        {:host    host
                         :port    port
                         :message bytes
                         :error   (.getMessage e)}))
        false))))

(defmulti send-message :type)
(defmethod send-message :error-response [{:keys [state remote-sid sa ta session-seq command seq-no function status csum a e]}]
  (log/info "Sending Response: ")
  (let [message             (h/error-response-msg remote-sid sa ta session-seq command seq-no function status csum a e )
        codec               (c/compile-codec state)
        ipmi-encode         (partial encode codec)
        encoded-message     (ipmi-encode message)]
    (safe (send-udp state encoded-message))))

(defmulti send-message :type)
(defmethod send-message :chassis-status [{:keys [state remote-sid seq-no a e]}]
  (log/info "Sending Status Chassis Response: ")
  (let [message             (h/chassis-status-response-msg remote-sid seq-no a e)
        codec               (c/compile-codec state)
        ipmi-encode         (partial encode codec)
        encoded-message     (ipmi-encode message)]
    (safe (send-udp state encoded-message))))

(defmethod send-message :device-id-req [{:keys [state remote-sid seq-no a e]}]
  (log/info "Sending Device ID  Response: ")
  (let [message             (h/device-id-response-msg remote-sid seq-no a e)
        codec               (c/compile-codec state)
        ipmi-encode         (partial encode codec)
        encoded-message     (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :chassis-reset [{:keys [state remote-sid session-seq seq-no a e]}]
  (log/info "Sending Chassis Reset Response")
  (let [message         (h/chassis-reset-response-msg remote-sid seq-no a e)
        codec           (c/compile-codec state)
        ipmi-encode     (partial encode codec)
        encoded-message (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :get-channel-auth-cap-req [state seq-no]
  (log/info "Sending Chassis Auth Capability Response")
  (let [message         (h/auth-capabilities-response-msg seq-no)
        codec           (c/compile-codec state)
        ipmi-encode     (partial encode codec)
        encoded-message (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :auth-capabiities-request-msg [{:keys [state]}]
  (let [message         (h/auth-capabilities-request-msg)
        codec           (c/compile-codec state)
        ipmi-encode     (partial encode codec)
        encoded-message (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :open-session-request [{:keys [state remote-sid server-sid a i c]}]
  (log/info "Sending Open Session Response ")
  (let [message         (h/rmcp-open-session-response-msg remote-sid server-sid a i c)
        codec           (c/compile-codec state)
        ipmi-encode     (partial encode codec)
        encoded-message (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :rmcp-rakp-2 [state]
  (log/info "Sending RAKP2")
  (let [message         (h/rmcp-rakp-2-response-msg)
        codec           (c/compile-codec state)
        ipmi-encode     (partial encode codec)
        encoded-message (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :rmcp-rakp-4 [{:keys [state server-sid]}]
  (log/info "Sending RAKP4")
  (let [message              (h/rmcp-rakp-4-response-msg server-sid)
        codec                (c/compile-codec state)
        ipmi-encode          (partial encode codec)
        encoded-message      (ipmi-encode message)]
    (safe (send-udp state encoded-message))))

(defmethod send-message :session-priv-level [{:keys [state remote-sid session-seq-no seq-no a e]}]
  (log/info "Sending Session Priv Level Response")
  (let [message             (h/set-session-priv-level-rsp-msg remote-sid session-seq-no seq-no a e)
        codec               (c/compile-codec state)
        ipmi-encode         (partial encode codec)
        encoded-message     (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :rmcp-close-session [{:keys [state remote-sid session-seq seq-no a e]}]
  (log/info "Sending Session Close Response")
  (let [message                 (h/rmcp-close-response-msg remote-sid session-seq seq-no a e)
        codec                   (c/compile-codec state)
        ipmi-encode             (partial encode codec)
        encoded-message         (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :asf-ping [{:keys [state message-tag]}]
  (log/info "Sending Ping Response")
  (let [message                     (h/presence-pong-msg message-tag)
        codec                       (c/compile-codec state)
        ipmi-encode                 (partial encode codec)
        encoded-message             (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :hpm-capabilities-req [{:keys [state remote-sid seq-no a e]}]
  (log/info "Sending HPM Capabilities Response")
  (let [message         (h/hpm-capabilities-response-msg remote-sid seq-no a e)
        codec           (c/compile-codec state)
        ipmi-encode     (partial encode codec)
        encoded-message (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :picmg-properties-req [{:keys [state remote-sid seq-no a e]}]
  (log/info "Sending PICMG Properties Response")
  (let [message         (h/picmg-response-msg remote-sid seq-no a e)
        codec           (c/compile-codec state)
        ipmi-encode     (partial encode codec)
        encoded-message (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

(defmethod send-message :vso-capabilities-req [{:keys [state remote-sid seq-no a e]}]
  (log/info "Sending VSO Capabilities Response")
  (let [message         (h/vso-response-msg remote-sid seq-no a e)
        codec           (c/compile-codec state)
        ipmi-encode     (partial encode codec)
        encoded-message (safe (ipmi-encode message))]
    (safe (send-udp state encoded-message))))

;; (defn send-rmcp-ack [session seq-no]
;;   (log/info "Sending rmcp-ack " seq-no)
;;   (let [message         (h/rmcp-ack seq-no)
;;         codec           (c/compile-codec (:hash input))
;;         ipmi-encode     (partial encode codec)
;;         encoded-message (safe (ipmi-encode message))]
;;     (send-message session encoded-message)))


(def ipmi-server-fsm
  [[(a/$ :init)
    (a/* :asm-ping)
    [:get-channel-auth-cap-req (a/$ :get-channel-auth-cap-req)
     :open-session-request (a/$ :open-session-request)
     :rmcp-rakp-1 (a/$ :rmcp-rakp-1)
     :rmcp-rakp-3 (a/$ :rmcp-rakp-3)]]
   [(a/*
     (a/or
      [:device-id-req (a/$ :device-id-req)]
      [:hpm-capabilities-req (a/$ :hpm-capabilities-req)]
      [:picmg-properties-req (a/$ :picmg-properties-req)]
      [:vso-capabilities-req (a/$ :vso-capabilities-req)]
      [:set-session-prv-level-req (a/$ :session-priv-level-req)]))]
   [(a/*
     (a/or
      [:chassis-status-req (a/$ :chassis-status-req)]
      [:chassis-reset-req (a/$ :chassis-reset-req)]))]
   [:rmcp-close-session-req (a/$ :rmcp-close-session-req)]])

(def ipmi-client-fsm
  [[(a/$ :init)
    (a/* :asm-ping)
    [:get-channel-auth-cap-rsp (a/$ :get-channel-auth-cap-rsp)
     :open-session-response (a/$ :open-session-response)
     :rmcp-rakp-2 (a/$ :rmcp-rakp-2)
     :rmcp-rakp-4 (a/$ :rmcp-rakp-4)]]
   [(a/*
     (a/or
      [:device-id-rsp (a/$ :device-id-rsp)]
      [:hpm-capabilities-rsp (a/$ :hpm-capabilities-rsp)]
      [:picmg-properties-rsp (a/$ :picmg-properties-rsp)]
      [:vso-capabilities-rsp (a/$ :vso-capabilities-rsp)]
      [:set-session-prv-level-rsp (a/$ :session-priv-level-rsp)]))]
   [(a/*
     (a/or
      [:chassis-status-rsp (a/$ :chassis-status-rsp)]
      [:chassis-reset-rsp (a/$ :chassis-reset-rsp)]))]
   [:rmcp-close-session-rsp (a/$ :rmcp-close-session-rsp)]])


;;TODO create schemas for send-message input to test handlers


(def ipmi-client-handler
  {:signal   :type
   :reducers {:init                     (fn [state _]
                                          #_(assoc state :state {}))
              :get-channel-auth-cap-req (fn [state input]
                                          (log/debug "Channel Auth Request")
                                          state)
              :get-channel-auth-cap-rsp (fn [state input]
                                          (log/info "Auth Capabilities Response")
                                          (let [seq   (get-seq-no input)
                                                state (assoc state :seq seq)]
                                            (send-message {:type :get-channel-auth-cap-req :input input :seq seq})
                                            state))
              :open-session-response    (fn [state input]
                                          (log/debug "Open Session Response")
                                          (let [a     (get input :a?)
                                                e     (get input :e?)
                                                state (assoc state :a? a :e? e)]
                                            state))
              :rmcp-rakp-2              (fn [state input]
                                          state)
              :rmcp-rakp-4              (fn [state input]
                                          state)
              :device-id-rsp            (fn [state input]
                                          (log/debug "Device ID Response")
                                          state)
              :hpm-capabilities-rsp     (fn [state input]
                                          state)
              :picmg-properties-rsp     (fn [state input]
                                          state)
              :vso-capabilities-rsp     (fn [state input]
                                          state)
              :session-priv-level-rsp   (fn [state input]
                                          state)
              :chassis-reset-rsp        (fn [state input]
                                          state)
              :rmcp-close-session-rsp   (fn [state input]
                                          state)}})
(comment
  """
    sidm     - Remote console session ID                      - remote-sid
    sidc     - BMC (Managed-System) session ID                - server-sid
    rm       - Remote console random number                   - remote-rn
    rc       - BMC (Managed-System) random number             - server-rn
    guidc    - BMC guid                                       - server-guid
    rolem    - Requested privilege level 
    unamem   - Username (absent for null user names) """)

(def ipmi-server-handler
  "This datatype needs to take in the transformed IPMI decode to drive the statemachine"
  {:signal   :type
   :reducers {:init                     (fn [state _]
                                          #_(assoc state :state {}))
              :error                    (fn [state _]
                                          (log/debug "State machine error handler")
                                          state)
              :asf-ping                 (fn [state input]
                                          (log/info "ASF PING")
                                          (let [h           (:hash input)
                                                message-tag (get :message-tag input)]
                                            (send-message  {:type :asf-ping :input input :message-tag message-tag})
                                            state))
              :get-channel-auth-cap-req (fn [state input]
                                          (log/info "Auth Capabilities Request")
                                          (let [h   (:hash input)
                                                seq (get input :seq-no 0)]
                                            (send-message {:type :get-channel-auth-cap-req :input input :seq seq})
                                            state))
              :open-session-request     (fn [state input]
                                          (log/info "Open Session Request " "Input" input "State" state)
                                          (let [server-sid (create-server-sid!)
                                                a          (get input :a)
                                                i          (get input :i)
                                                c          (get input :c)
                                                remote-sid (get input :remote-sid)
                                                rolem      (get input :rolem)
                                                auth-codec (get input :auth-codec)
                                                conf-codec (get input :conf-codec)
                                                state      (assoc state
                                                                  :remote-sid  remote-sid
                                                                  :server-sid  server-sid
                                                                  :rolem rolem
                                                                  :auth-codec auth-codec
                                                                  :conf-codec conf-codec)
                                                m          {:type       :open-session-request
                                                            :a          a
                                                            :i          i
                                                            :c          c
                                                            :input      input
                                                            :remote-sid remote-sid
                                                            :server-sid server-sid}]
                                            (send-message  m)
                                            state))
              :rmcp-rakp-1              (fn [state input]
                                          (log/info "RAKP-1 Request" "input" {:input input :state state})
                                          (let [remote-sid  (get state :remote-sid)
                                                server-sid  (get state :server-sid)
                                                auth        (get state :auth-codec)
                                                unamem      (get input :unamem)
                                                remote-rn   (get input :remote-rn)
                                                rolem       (get input :rolem)
                                                server-rn   (create-server-rn!)
                                                uid         (r/lookup-password-key unamem)
                                                server-guid (r/get-device-guid-bytes unamem)
                                                state       (assoc state
                                                                   :unamem unamem
                                                                   :remote-rn remote-rn
                                                                   :rolem rolem
                                                                   :server-rn server-rn
                                                                   :server-guid server-guid)]
                                            (condp = auth
                                              :rmcp-rakp           (let [m {:type        :rmcp-rakp-2
                                                                            :input       input
                                                                            :auth        auth
                                                                            :server-rn   server-rn
                                                                            :unamem      unamem
                                                                            :rolem       rolem
                                                                            :remote-sid  remote-sid
                                                                            :status      0
                                                                            :server-guid server-guid
                                                                            :rakp2-hmac  [0]}]
                                                                     (send-message m))
                                              :rmcp-rakp-hmac-sha1 (if-not (nil? (r/lookup-userid unamem))
                                                                     (let [rakp2-hmac (vec (calc-rakp-1 {:remote-rn   remote-rn
                                                                                                         :server-rn   server-rn
                                                                                                         :server-guid server-guid
                                                                                                         :remote-sid  remote-sid
                                                                                                         :server-sid  server-sid
                                                                                                         :unamem      unamem
                                                                                                         :rolem       rolem
                                                                                                         :uid         uid}))
                                                                           m          {:type        :rmcp-rakp-2
                                                                                       :input       input
                                                                                       :remote-sid  remote-sid
                                                                                       :server-rn   server-rn
                                                                                       :unamem      unamem
                                                                                       :status      0x00
                                                                                       :server-guid server-guid
                                                                                       :rakp2-hmac  rakp2-hmac
                                                                                       :auth        auth}]
                                                                       (send-message m))
                                                                     (let [m {:type        :rmcp-rakp-2
                                                                              :input       input
                                                                              :auth        auth
                                                                              :server-rn   server-rn
                                                                              :remote-sid  remote-sid
                                                                              :status      0x0D
                                                                              ;; TODO enum error codes
                                                                              :server-guid (-> uuid/null uuid/as-byte-array vec)
                                                                              ;;TODO create device quid outside registrar?
                                                                              :rakp2-hmac  [0]}]
                                                                       (log/error (format "User %s  Not Found.." unamem))
                                                                       (send-message m)))
                                              :rmcp-rakp)
                                            (log/spy state)))

              :rmcp-rakp-3 (fn [state input]
                             (log/info "RAKP-3 Request ")
                             (let [h           (:hash input)
                                   remote-sid  (get state :remote-sid)
                                   server-sid  (get state  :server-sid)
                                   rolem       (get state :rolem)
                                   server-rn   (get state :server-rn)
                                   remote-rn   (get state :remote-rn)
                                   unamem      (get state :unamem)
                                   login-state (c/get-login-state h)
                                   auth        (get state :auth-codec)
                                   server-guid (get state :server-guid)
                                   uid         (r/lookup-password-key unamem)
                                   ]

                               (condp = auth
                                 :rmcp-rakp           (let [m {:type       :rmcp-rakp-4
                                                               :auth       auth
                                                               :input      input
                                                               :server-sid server-sid}]
                                                        (send-message m)
                                                        state)
                                 :rmcp-rakp-hmac-sha1 (let [kec          (get input :kec 0)
                                                            sidc-hmac    (calc-rakp-3 {:remote-sid remote-sid
                                                                                       :server-rn  server-rn
                                                                                       :rolem      rolem
                                                                                       :unamem     unamem
                                                                                       :uid        uid})
                                                            sik          (calc-rakp-4-sik {:remote-rn remote-rn
                                                                                           :server-rn server-rn
                                                                                           :rolem     rolem
                                                                                           :unamem    unamem
                                                                                           :uid       uid})
                                                            sidm-hmac    (calc-rakp-4-sidm {:remote-rn   remote-rn
                                                                                            :server-sid  server-sid
                                                                                            :server-guid server-guid
                                                                                            :sik         sik
                                                                                            :uid         uid})
                                                            sidm-hmac-96 (-> sidm-hmac (bytes/slice 0 12) vec)
                                                            m            {:type       :rmcp-rakp-4
                                                                          :auth       auth
                                                                          :input      input
                                                                          :server-sid server-sid
                                                                          :sidm-hmac  sidm-hmac-96}
                                                            state        (assoc state :sidm-hmac sidm-hmac-96 :sik (vec sik))]
                                                        ;(upsert-sik (vec sik) h)
                                                        (send-message m)
                                                        state))))

              :hpm-capabilities-req   (fn [state input]
                                        (log/info "HPM Capabilities")
                                        (let [h               (:hash input)
                                              seq             (get input :session-seq 0) seq-no (get input :seq-no 0)
                                              completion-code 0xC1
                                              c               (get input :command)
                                              f               (get input :response)
                                              remote-sid      (get state :remote-sid)]
                                        ;ta  | nf |  hcsum | sa | slun | c   | cc | csum
                                          (send-message  {:type       :hpm-capabilities-req
                                                          :input      input
                                                          :remote-sid remote-sid
                                                          :seq        seq
                                                          :ta         0x81
                                                          :function   f
                                                          :hcsum      0xcb
                                                          :sa         0x81
                                                          :seq-no     seq-no
                                                          :sl         0x08
                                                          :command    c
                                                          :status     0xC1
                                                          :csum       0x17
                                                          :a          (:a? input)
                                                          :e          (:e? input)}))
                                        state)
              :picmg-properties-req   (fn [state input]
                                        (log/info "PICMG Properties")
                                        (let [h               (:hash input)
                                              seq-no          (get input :seq-no 0)
                                              session-seq     (get input :session-seq 0)
                                              completion-code 0xC1
                                              remote-sid      (get state :remote-sid)
                                              c               (:command input)
                                              f               (:response input)]
                                        ;ta  | nf |  hcsum | sa | slun | c   | cc | csum
                                          (send-message {:type       :error-response
                                                         :input      input
                                                         :remote-sid remote-sid
                                                         :seq        session-seq
                                                         :ta         0
                                                         :function   f
                                                         ;;TODO Fix calc for this
                                                         :hcsum      0xcb
                                                         :sa         0
                                                         :seq-no     seq-no
                                                         :sl         0x10
                                                         :command    c
                                                         :status     0xC1
                                                         ;;TODO Fix calc for this
                                                         :csum       0x17
                                                         :a          (:a? input)
                                                         :e          (:e? input)}))
                                        state)
              :vso-capabilities-req   (fn [state input]
                                        (log/info "VSO Capabilities")
                                        (let [h               (:hash input)
                                              seq-no          (get input :seq-no 0)
                                              session-seq     (get input :session-seq 0)
                                              completion-code 0xC1
                                              remote-sid      (get state :remote-sid)
                                              c               (:command input)
                                              f               (:response input)]
                                          (send-message  {:type        :vso-capabilities-req
                                                          :input       input
                                                          :remote-sid  remote-sid
                                                          :sa          0
                                                          :ta          0
                                                          :session-seq session-seq
                                                          :command     c
                                                          :seq-no      seq-no
                                                          :function    f
                                                          :status      0
                                                          ;;TODO fix
                                                          :csum        204
                                                          :a           (:a? input)
                                                          :e           (:e? input)}))
                                        state)
              :device-id-req          (fn [state input]
                                        (log/info "Device ID Request")
                                        (let [h          (:hash input)
                                              remote-sid (get state :remote-sid)
                                              seq-no     (get input :seq-no)]
                                          (send-message  {:type       :device-id-req
                                                          :input      input
                                                          :remote-sid remote-sid
                                                          :seq-no     seq-no
                                                          :a          (:a? input)
                                                          :e          (:e? input)})
                                          state))
              :session-priv-level-req (fn [state input]
                                        (log/info "Set Session Priv Level")
                                        (let [h          (:hash input)
                                              remote-sid (get state :remote-sid)
                                              seq        (get input :session-seq 0)
                                              seq-no     (get input :seq-no 0)]
                                          (send-message  {:type           :session-priv-level
                                                          :input          input
                                                          :remote-sid     remote-sid
                                                          :seq-no         seq-no
                                                          :session-seq-no seq
                                                          :a              (:a? input)
                                                          :e              (:e? input)})
                                          state))
              :rmcp-close-session-req (fn [state input]
                                        (log/info "Session Closing ")
                                        (let [h          (:hash input)
                                              remote-sid (get state :remote-sid)
                                              seq        (get input :session-seq 0)
                                              seq-no     (get input :seq-no 0)]
                                          (send-message  {:type       :rmcp-close-session
                                                          :input      input
                                                          :remote-sid remote-sid
                                                          :seq        seq
                                                          :seq-no     seq-no
                                                          :a          (:a? input)
                                                          :e          (:e? input)})
                                          state))
              ;; COMMAND HANDLERS
              :chassis-status-req     (fn [state input]
                                        (log/info "Chassis Status Request")
                                        (let [h          (:hash input)
                                              server-sid (get state :server-sid)
                                              seq-no     (get input :seq-no)]
                                          #_(send-message  {:type   :chassis-status
                                                            :input  input
                                                            :sid    server-sid
                                                            :seq-no seq-no
                                                            :a      (:a? input)
                                                            :e      (:e? input)})
                                          state))

              :chassis-reset-req (fn [state input]
                                   (log/info "Chassis Reset Request")
                                   (let [h           (:hash input)
                                         server-sid  (get state :server-sid)
                                         seq-no      (get input :seq-no)
                                         session-seq (get input :session-seq)
                                         unamem      (get state :unamem)]
                                     #_(if-not (nil? (r/get-driver-device-id (keyword unamem)))
                                         (do
                                           (safe (r/reboot-server {:driver :packet :user-key (keyword unamem)}))
                                           (send-message  {:type        :chassis-reset
                                                           :input       input
                                                           :sid         server-sid
                                                           :session-seq seq
                                                           :seq-no      seq-no
                                                           :status      0
                                                           :a           (:a? input)
                                                           :e           (:e? input)})))
                                     state))}})

(defn view-client-fsm []
  (automat.viz/view (a/compile ipmi-client-fsm ipmi-client-handler)))

(defn view-server-fsm []
  (automat.viz/view (a/compile ipmi-server-fsm ipmi-server-handler)))
