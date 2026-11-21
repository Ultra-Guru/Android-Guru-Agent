package com.apex.agent.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GithubApiService 纯函数契约（Git 审计 P0-4 / P1-10/11）：
 * resolveApiUrl host 白名单（nextUrl 篡改防线）+ retryDelayMs 幂等重试矩阵。
 */
class GithubApiServiceContractTest {

    // ═══ resolveApiUrl（P0-4）═══

    @Test
    fun `相对路径补全基址`() {
        assertEquals("https://api.github.com/user", GithubApiService.resolveApiUrl("/user"))
    }

    @Test
    fun `合法分页 nextUrl 原样跟随`() {
        val next = "https://api.github.com/repos/o/r/issues?page=2"
        assertEquals(next, GithubApiService.resolveApiUrl(next))
    }

    @Test
    fun `跨主机 nextUrl 拒绝`() {
        val malicious = "https://evil.example.com/collect?token=x"
        try {
            GithubApiService.resolveApiUrl(malicious)
            error("跨主机 URL 应被拒绝")
        } catch (e: GithubApiException) {
            assertTrue(e.message!!.contains("evil.example.com"))
        }
    }

    @Test
    fun `http 明文拒绝`() {
        try {
            GithubApiService.resolveApiUrl("http://api.github.com/user")
            error("http URL 应被拒绝")
        } catch (e: GithubApiException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun `相似主机拒绝`() {
        // api.github.com.evil.com 的 owner 形态误解析防线
        try {
            GithubApiService.resolveApiUrl("https://api.github.com.evil.com/x")
            error("相似主机应被拒绝")
        } catch (e: GithubApiException) {
            assertTrue(e.message!!.contains("api.github.com.evil.com"))
        }
    }

    // ═══ retryDelayMs（P1-10/11）═══

    @Test
    fun `GET 可重试且尊重 Retry-After 封顶`() {
        assertEquals(30_000L, GithubApiService.retryDelayMs("GET", 429, 0, 30))
        assertEquals(60_000L, GithubApiService.retryDelayMs("GET", 429, 1, 600))
        assertEquals(5_000L, GithubApiService.retryDelayMs("GET", 503, 0, null))
    }

    @Test
    fun `POST 与 PUT 一律不自动重试`() {
        assertNull(GithubApiService.retryDelayMs("POST", 429, 0, 5))
        assertNull(GithubApiService.retryDelayMs("POST", 500, 0, null))
        assertNull(GithubApiService.retryDelayMs("PUT", 503, 0, 5))
        assertNull(GithubApiService.retryDelayMs("DELETE", 500, 0, null))
    }

    @Test
    fun `重试预算用尽后停止`() {
        assertNull(GithubApiService.retryDelayMs("GET", 429, 3, 5))
        assertNull(GithubApiService.retryDelayMs("GET", 500, 2, null))
    }

    @Test
    fun `非重试状态码不重试`() {
        assertNull(GithubApiService.retryDelayMs("GET", 404, 0, null))
        assertNull(GithubApiService.retryDelayMs("GET", 401, 0, null))
    }
}
