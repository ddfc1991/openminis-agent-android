package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.6 i14] the embedded Clash core only ever listens on 127.0.0.1, with a secret; imported YAML keeps its nodes. */
class ClashConfigCoreTest {
    @Test fun overrideDropsOwnedKeysAndTun() {
        val y = "port: 7890\nsocks-port: 7891\nallow-lan: true\nexternal-controller: 0.0.0.0:9090\nsecret: ''\ntun:\n  enable: true\n  stack: system\nmode: rule\nproxies:\n  - name: a\n    type: ss\n    server: 1.2.3.4\nrules:\n  - MATCH,a\n"
        val o = ClashConfigCore.override(y, "s3cr3t")
        assertTrue(o.startsWith("mixed-port: 7898\nallow-lan: false\nbind-address: 127.0.0.1\nexternal-controller: 127.0.0.1:9097\nsecret: \"s3cr3t\""))
        assertFalse(o.contains("7890")); assertFalse(o.contains("0.0.0.0")); assertFalse(o.contains("enable: true")); assertFalse(o.contains("stack:"))
        assertTrue(o.contains("proxies:\n  - name: a")); assertTrue(o.contains("MATCH,a")); assertTrue(o.contains("mode: rule"))
        assertEquals(1, Regex("(?m)^secret:").findAll(o).count())
    }

    @Test fun fromNodesHasGroupsAndGoogleRules() {
        val o = ClashConfigCore.fromNodes(listOf(ClashConfigCore.Node("自己填代理", "http", "h.test", 3128, "u", "p\"w")), "k")
        assertTrue(o.contains("type: url-test")); assertTrue(o.contains("type: fallback")); assertTrue(o.contains("interval: 30")); assertTrue(o.contains("lazy: false")); assertTrue(o.contains("expected-status: 204")); assertTrue(o.contains("max-failed-times: 2"))   // [38.9.11.3] live node, real check
        assertTrue(o.contains("DOMAIN-SUFFIX,gmail.com,代理")); assertTrue(o.contains("DOMAIN-SUFFIX,cn,DIRECT")); assertTrue(o.contains("MATCH,代理"))
        assertTrue(o.contains("password: \"p\\\"w\""))
        assertTrue(ClashConfigCore.looksLikeClashYaml(o)); assertFalse(ClashConfigCore.looksLikeClashYaml("c3M6Ly9hYmM="))
    }
}
