package com.bangbang93.onesideatask.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.throwable.shouldHaveMessage
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Behavior tests for [OnesClient] against a local mock of the ONES Open API v2,
 * served by the JDK built-in [HttpServer] on an ephemeral port (port 0).
 *
 * The mock captures the `Authorization` header and the request URI of every
 * request so the tests can assert both the auth model (personal API key sent
 * as a Bearer header) and the exact request paths/queries. The server is
 * started in `beforeTest` and stopped in `afterTest`, so it is shut down even
 * when a test fails.
 */
class OnesClientSpec : FunSpec({

    val server = MockOnesServer()

    beforeTest { server.start() }

    afterTest { (_, _) -> server.stop() }

    test("getIssues returns the raw JSON body and sends Bearer auth with the list query") {
        val body = """{"result":"SUCCESS","data":{"list":[{"id":"issue-1","title":"写周报"}]}}"""
        server.respondWith(200, body)

        val json = OnesClient.getIssues(server.url, "team-1", "test-key", 20)

        json shouldBe body
        server.capturedAuthorization.get() shouldBe "Bearer test-key"
        server.capturedPath.get() shouldBe "/openapi/v2/project/issues"
        server.capturedQuery.get() shouldContain "teamID=team-1"
        server.capturedQuery.get() shouldContain "limit=20"
    }

    test("getIssues URL-encodes the teamID and trims a trailing slash from the server URL") {
        server.respondWith(200, """{"result":"SUCCESS"}""")

        OnesClient.getIssues("${server.url}/", "team 1&x", "test-key", 5)

        server.capturedPath.get() shouldBe "/openapi/v2/project/issues"
        server.capturedQuery.get() shouldContain "teamID=team+1%26x"
        server.capturedQuery.get() shouldContain "limit=5"
    }

    test("getIssue requests the issue path with the teamID query and Bearer auth") {
        server.respondWith(200, """{"result":"SUCCESS","data":{"id":"abc-123"}}""")

        val json = OnesClient.getIssue(server.url, "team-1", "test-key", "abc-123")

        json shouldContain "\"id\":\"abc-123\""
        server.capturedAuthorization.get() shouldBe "Bearer test-key"
        server.capturedPath.get() shouldBe "/openapi/v2/project/issues/abc-123"
        server.capturedQuery.get() shouldBe "teamID=team-1"
    }

    test("getTeams requests the teams path without a query and with Bearer auth") {
        server.respondWith(200, """{"result":"SUCCESS","data":[{"id":"team-1"}]}""")

        OnesClient.getTeams(server.url, "test-key")

        server.capturedAuthorization.get() shouldBe "Bearer test-key"
        server.capturedPath.get() shouldBe "/openapi/v2/account/teams"
        server.capturedQuery.get() shouldBe null
    }

    test("HTTP 401 is mapped to an invalid-API-key error") {
        server.respondWith(401, "")

        val exception = shouldThrowExactly<IllegalStateException> {
            OnesClient.getIssues(server.url, "team-1", "test-key", 20)
        }

        exception shouldHaveMessage "API key 无效或已过期"
    }

    test("HTTP 403 is mapped to a permission error") {
        server.respondWith(403, "")

        val exception = shouldThrowExactly<IllegalStateException> {
            OnesClient.getIssues(server.url, "team-1", "test-key", 20)
        }

        exception shouldHaveMessage "权限不足：请检查 scope 与业务权限"
    }

    test("HTTP 404 is mapped to a not-found error carrying the issue id") {
        server.respondWith(404, "")

        val exception = shouldThrowExactly<FileNotFoundException> {
            OnesClient.getIssue(server.url, "team-1", "test-key", "issue-404")
        }

        exception shouldHaveMessage "工作项不存在: issue-404"
    }

    test("HTTP 200 with a FAIL envelope throws the API's errorMsg") {
        server.respondWith(200, """{"result":"FAIL","errorMsg":"boom"}""")

        val exception = shouldThrowExactly<IllegalStateException> {
            OnesClient.getIssues(server.url, "team-1", "test-key", 20)
        }

        exception shouldHaveMessage "boom"
    }

    test("other HTTP errors are mapped to an IOException carrying the status code") {
        server.respondWith(500, "internal error")

        val exception = shouldThrowExactly<IOException> {
            OnesClient.getTeams(server.url, "test-key")
        }

        exception.message shouldContain "HTTP 500"
    }

    test("an HTTP error with an empty body omits the detail suffix from the message") {
        server.respondWith(502, "")

        val exception = shouldThrowExactly<IOException> {
            OnesClient.getTeams(server.url, "test-key")
        }

        exception shouldHaveMessage "ONES API 请求失败 (HTTP 502)"
    }

    test("getIssues passes limit=0 and negative limits through in the query unchanged") {
        server.respondWith(200, """{"result":"SUCCESS"}""")

        OnesClient.getIssues(server.url, "team-1", "test-key", 0)
        server.capturedQuery.get() shouldContain "limit=0"

        OnesClient.getIssues(server.url, "team-1", "test-key", -5)
        server.capturedQuery.get() shouldContain "limit=-5"
    }

    test("getIssue percent-encodes URL-special characters in the issue id path segment") {
        server.respondWith(200, """{"result":"SUCCESS"}""")

        OnesClient.getIssue(server.url, "team-1", "test-key", "a/b?x")

        server.capturedRawPath.get() shouldBe "/openapi/v2/project/issues/a%2Fb%3Fx"
    }

    test("a FAIL envelope without errorMsg falls back to the generic failure message") {
        server.respondWith(200, """{"result":"FAIL"}""")

        val exception = shouldThrowExactly<IllegalStateException> {
            OnesClient.getTeams(server.url, "test-key")
        }

        exception shouldHaveMessage "ONES API 请求失败"
    }
})

/**
 * Minimal local ONES mock: JDK [HttpServer] bound to an ephemeral port.
 * Records the `Authorization` header and request URI of the last request and
 * replies with the configured status/body. Test-only helper — production code
 * must go through the platform `HttpRequests` (proxy support).
 */
private class MockOnesServer {
    private var server: HttpServer? = null

    val capturedAuthorization = AtomicReference<String?>()
    val capturedPath = AtomicReference<String?>()
    val capturedRawPath = AtomicReference<String?>()
    val capturedQuery = AtomicReference<String?>()

    @Volatile private var status = 200
    @Volatile private var body = ""

    fun start() {
        val httpServer = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        httpServer.createContext("/") { exchange -> handle(exchange) }
        httpServer.start()
        server = httpServer
    }

    val url: String
        get() = "http://$LOOPBACK:${checkNotNull(server).address.port}"

    fun respondWith(statusCode: Int, responseBody: String) {
        status = statusCode
        body = responseBody
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        capturedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
        capturedPath.set(exchange.requestURI.path)
        capturedRawPath.set(exchange.requestURI.rawPath)
        capturedQuery.set(exchange.requestURI.rawQuery)
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(status, NO_BODY)
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val NO_BODY = -1L
    }
}
