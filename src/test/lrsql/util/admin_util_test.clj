(ns lrsql.util.admin-util-test
  (:require [clojure.test :refer [deftest testing is]]
            [lrsql.util.admin :as ua]
            [lrsql.util :as u]))

;; TODO: Replace with a gentest
(deftest password-test
  (testing "password hashing and verification"
    (let [pass-hash-m (ua/hash-password "foo")]
      (is (ua/valid-password? "foo" pass-hash-m))
      (is (not (ua/valid-password? "pass" pass-hash-m))))))

(deftest jwt-test
  (let [test-id (u/str->uuid "00000000-0000-0000-0000-000000000001")]
    (testing "JSON web tokens"
      (is (re-matches #".*\..*\..*" (ua/account-id->jwt test-id 3600)))
      (is (= test-id
             (-> test-id (ua/account-id->jwt 3600) (ua/jwt->account-id 1))))
      (is (= :lrsql.admin/invalid-token-error
             (ua/jwt->account-id "not-a-jwt" 3600)))
      (is (= :lrsql.admin/expired-token-error
             (let [tok (ua/account-id->jwt test-id 1)
                   _   (Thread/sleep 1001)]
                 (ua/jwt->account-id tok 0)))))))
