package com.pai.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAssetVerifierTest {

    @Test fun validHtmlIsOk() {
        val h = "<html><head><title>t</title></head><body><div><script>if (a<b) { x() }</script></div></body></html>"
        assertTrue(WebAssetVerifier.validateHtml(h).ok)
    }

    @Test fun unclosedTagFails() {
        assertFalse(WebAssetVerifier.validateHtml("<div><span>x</span>").ok)
    }

    @Test fun optionalTagsMayBeOmitted() {
        assertTrue(WebAssetVerifier.validateHtml("<ul><li>a<li>b</ul>").ok)
    }

    @Test fun voidTagsAreFine() {
        assertTrue(WebAssetVerifier.validateHtml("<div><br><img src=x></div>").ok)
    }

    @Test fun jsBalancedIsOk() {
        assertTrue(WebAssetVerifier.validateJs("function f(){ return [1,2]; }").ok)
    }

    @Test fun jsUnbalancedFails() {
        assertFalse(WebAssetVerifier.validateJs("function f(){ return 1;").ok)
    }

    @Test fun jsBracesInsideStringAreIgnored() {
        assertTrue(WebAssetVerifier.validateJs("var s = \"}\"; ok()").ok)
    }

    @Test fun cssBalancedIsOk() {
        assertTrue(WebAssetVerifier.validateCss(".a { color: red; }").ok)
    }

    @Test fun cssUnbalancedFails() {
        assertFalse(WebAssetVerifier.validateCss(".a { color: red;").ok)
    }

    @Test fun dispatchesByExtension() {
        assertFalse(WebAssetVerifier.validate("index.html", "<div>").ok)
        assertTrue(WebAssetVerifier.validate("app.js", "var a = 1;").ok)
        assertTrue(WebAssetVerifier.validate("styles.css", "a{}").ok)
    }
}