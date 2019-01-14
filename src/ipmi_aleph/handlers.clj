(ns ipmi-aleph.handlers)

; Page 126
(defn rmcp-ack [seqno]
  {:version 6,
   :reserved 0,
   :sequence seqno,
   :rmcp-class
   {:type :rmcp-ack}})

(defn chassis-status-response-msg [sid]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:ipmi-session-payload
    {:ipmi-2-0-payload
     {:session-id sid,
      :session-seq 6,
      :payload-type {:encrypted? false, :authenticated? false, :type 0},
      :power-state
      {:reserved false,
       :power-restore-policy 0,
       :power-control-fault false,
       :power-fault false,
       :interlock false,
       :overload false,
       :power-on? true},
      :last-power-event
      {:reserved 0,
       :last-power-on-state-via-ipmi false,
       :last-power-down-state-power-fault false,
       :last-power-down-state-interlock-activated false,
       :last-power-down-state-overloaded false,
       :last-power-down-ac-failed false},
      :command 1,
      :source-lun 24,
      :source-address 32,
      :misc-chassis-state
      {:reserved false,
       :chassis-identify-command-state-info-supported false,
       :chassis-identify-state-supported 0,
       :cooling-fan-fault-detect false,
       :drive-fault false,
       :front-panel-lockout false,
       :chassis-intrusion-active false},
      :checksum 198,
      :header-checksum 123,
      :target-address 129,
      :network-function {:function 1, :target-lun 0},
      :completion-code 0,
      :message-length 11},
     :type :ipmi-2-0-session}
    :type :ipmi-session}})

(defn chassis-reset-response-msg [sid seq]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:ipmi-session-payload
    {:ipmi-2-0-payload
     {:session-id sid,
      :session-seq seq,
      :payload-type {:encrypted? false, :authenticated? false, :type 0},
      :command 2,
      :source-lun 24,
      :source-address 32,
      :checksum 198,
      :header-checksum 123,
      :target-address 129,
      :network-function {:function 1, :target-lun 0},
      :message-length 8,
      :command-completion-code 0},
     :type :ipmi-2-0-session},
    :type :ipmi-session}})

(defn device-id-response-msg [sid]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:ipmi-session-payload
    {:ipmi-2-0-payload
     {:session-id sid,
      :major-firmware-revision 8,
      :session-seq 3,
      :payload-type {:encrypted? false, :authenticated? false, :type 0},
      :device-id 0,
      :additional-device-support
      {:chassis true,
       :bridge false,
       :event-generator false,
       :event-receiver true,
       :fru-invetory true,
       :sel true,
       :sdr-repository true,
       :sensor true},
      :device-revision
      {:provides-sdr false, :reserved 0, :device-revision 3},
      :command 1,
      :source-lun 12,
      :auxiliary-firmware 0,
      :source-address 32,
      :manufacturer-id [145 18 0],
      :checksum 106,
      :header-checksum 99,
      :target-address 129,
      :network-function {:function 7, :target-lun 0},
      :message-length 23,
      :command-completion-code 0,
      :product-id 3842,
      :ipmi-version 2,
      :device-availability
      {:operation false, :major-firmware-revision 9}},
     :type :ipmi-2-0-session},
    :type :ipmi-session}})

(defn set-session-priv-level-rsp-msg [sid]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:ipmi-session-payload
    {:ipmi-2-0-payload
     {:session-id sid,
      :session-seq 0
      :payload-type {:encrypted? false, :authenticated? false, :type 0},
      :command 59,
      :source-lun 4,
      :source-address 32,
      :checksum 157,
      :header-checksum 99,
      :target-address 129,
      :network-function {:function 7, :target-lun 0},
      :completion-code 0,
      :message-length 9,
      :privilege-level {:reserved 0, :priv-level 4}},
     :type :ipmi-2-0-session},
    :type :ipmi-session}})

; Page 128
(defn presence-ping-msg [tag]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:asf-payload
    {:iana-enterprise-number 4542,
     :asf-message-header
     {:asf-message-type 128,
      :message-tag tag,
      :reserved 0,
      :data-length 0}},
    :type :asf-session}})

(defn presence-pong-msg [tag]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:asf-payload
    {:iana-enterprise-number 4542,
     :asf-message-header
     {:asf-message-type 64,
      :reserved2 [0 0 0 0 0 0],
      :data-length 16,
      :oem-defined 0,
      :supported-interactions 0,
      :message-tag tag,
      :reserved1 0,
      :oem-iana-number 4542,
      :supported-entities 0}},
    :type :asf-session}})

(defn auth-capabilities-response-msg []
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class {:ipmi-session-payload {:ipmi-1-5-payload {:session-seq 0,
                                                          :session-id 0,
                                                          :message-length 16,
                                                          :ipmb-payload {:oem-id [0 0 0],
                                                                         :oem-aux-data 0,
                                                                         :auth-compatibility {:reserved 0,
                                                                                              :key-generation false,
                                                                                              :per-message-auth false,
                                                                                              :user-level-auth false,
                                                                                              :non-null-user-names true,
                                                                                              :null-user-names false,
                                                                                              :anonymous-login-enabled false},
                                                                         :version-compatibility
                                                                         {:version-compatibility true,
                                                                          :reserved false,
                                                                          :oem-proprietary-auth false,
                                                                          :password-key true,
                                                                          :md5-support true,
                                                                          :md2-support true,
                                                                          :no-auth-support true},
                                                                         :command 56,
                                                                         :channel {:reserved 0, :channel-num 1},
                                                                         :source-lun 0,
                                                                         :source-address 32,
                                                                         :supported-connections {:reserved 0, :ipmi-2-0 true, :ipmi-1-5 true},
                                                                         :checksum 9,
                                                                         :header-checksum 99,
                                                                         :target-address 129,
                                                                         :network-function {:function 7, :target-lun 0},
                                                                         :command-completion-code 0}},
                                       :type :ipmi-1-5-session},
                :type :ipmi-session}})


;; {:version 6,
;;  :reserved 0,
;;  :sequence 255,
;;  :rmcp-class {:ipmi-session-payload
;;               {:ipmi-2-0-payload
;;                {:session-id [0 0 0 0],
;;                 :session-seq [0 0 0 0],
;;                 :payload-type
;;                 {:encrypted? false,
;;                  :authenticated? false,
;;                  :type 17},
;;                 :authentication-payload
;;                 {:type 0,
;;                  :reserved [0 0 0],
;;                  :length 8,
;;                  :algo {:reserved 0, :algorithm 1}},
;;                 :integrity-payload
;;                 {:type 1,
;;                  :reserved [0 0 0],
;;                  :length 8,
;;                  :algo {:reserved 0, :algorithm 1}},
;;                 :status-code 0,
;;                 :remote-session-id 2762187424,
;;                 :message-tag 0,
;;                 :managed-system-session-id 2181300224,
;;                 :reserved 0,
;;                 :message-length 36,
;;                 :confidentiality-payload
;;                 {:type 2,
;;                  :reserved [0 0 0],
;;                  :length 8,
;;                  :algo {:reserved 0, :algorithm 1}},
;;                 :privilege-level
;;                 {:reserved 0,
;;                  :max-priv-level 0}},
;;                :type :ipmi-2-0-session},
;;               :type :ipmi-session}}


(defn rmcp-close-response-msg [sid seq]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:ipmi-session-payload
    {:ipmi-2-0-payload
     {:session-id sid,
      :session-seq seq
      :payload-type {:encrypted? false, :authenticated? false, :type 0},
      :command 60,
      :source-lun 28,
      :source-address 32,
      :checksum 136,
      :header-checksum 99,
      :target-address 129,
      :network-function {:function 7, :target-lun 0},
      :completion-code 0,
      :message-length 8},
     :type :ipmi-2-0-session},
    :type :ipmi-session}})

(defn rmcp-open-session-response-msg [rsid mssid]
  (comment Page 148)
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:ipmi-session-payload
    {:ipmi-2-0-payload
     {:session-id 0
      :session-seq 0
      :payload-type {:encrypted? false, :authenticated? false, :type 17},
      :authentication-payload
      {:type 0,
       :reserved [0 0 0],
       :length 8,
       :algo {:reserved 0, :algorithm 0}},
      :integrity-payload
      {:type 1,
       :reserved [0 0 0],
       :length 8,
       :algo {:reserved 0, :algorithm 0}},
      :status-code 0,
      :remote-session-id rsid,
      :message-tag 0,
      :managed-system-session-id mssid
      :reserved 0,
      :message-length 36,
      :confidentiality-payload
      {:type 2,
       :reserved [0 0 0],
       :length 8,
       :algo {:reserved 0, :algorithm 0}},
      :privilege-level {:reserved 0, :max-priv-level 0}},
     :type :ipmi-2-0-session},
    :type :ipmi-session}})

(defn rmcp-rakp-2-response-msg [rsid msrand msguid]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class
   {:ipmi-session-payload
    {:ipmi-2-0-payload
     {:session-id 0
      :session-seq 0
      :payload-type
      {:encrypted? false,
       :authenticated? false,
       :type 19},
      :managed-system-random-number msrand
      :status-code 0,
      :message-tag 0,
      :reserved [0 0],
      :message-length 40,
      :managed-system-guid msguid,
      :remote-session-console-id rsid},
     :type :ipmi-2-0-session}
    :type :ipmi-session}})

(defn rmcp-rakp-4-response-msg [rsid]
  {:version 6,
   :reserved 0,
   :sequence 255,
   :rmcp-class {:ipmi-session-payload
                {:ipmi-2-0-payload
                 {:payload-type
                  {:encrypted? false,
                   :authenticated? false,
                   :type 21},
                  :session-seq 0,
                  :session-id 0
                  :message-length 8,
                  :message-tag 0,
                  :status-code 0,
                  :reserved [0 0],
                  :managed-console-session-id rsid},
                 :type :ipmi-2-0-session},
                :type :ipmi-session}})

