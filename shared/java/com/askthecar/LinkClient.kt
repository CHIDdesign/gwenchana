package com.askthecar

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 서버가 본 두 차 연결 상태. partner = 두 사람 모두 동의했을 때만 오는 상대 주행 기록(진술·연락처·안내 단계 없음) */
data class LinkState(val state: String, val code: String? = null, val expiresIn: Int = 0,
                     val meAgreed: Boolean = false, val partnerAgreed: Boolean = false, val partner: HandoffData? = null) {
    companion object { const val NONE = "none"; const val CODE = "code"; const val LINKED = "linked" }
}

/** 비슷한 시각·장소에 기록된 다른 사고 하나. 누구인지·어디인지는 서버가 안 준다(dt = 상대 사고 시각 - 내 사고 시각, 초) */
data class LinkHint(val dt: Int, val withinM: Int)

/**
 * 두 차 기록 연결 통로 (서버: game/bridge/sync_server.py, 규칙은 그 파일 맨 위). SyncClient 와 같은 계정·주소를 쓴다.
 * 모두 막히는(blocking) 호출 → IO 스레드에서 부를 것. 실패하면 null/false.
 * shortcut: 인증 없음(계정 = 시연용 운전자 id) — 실제 서비스는 계정 토큰 + https 로 바꿔야 함
 */
class LinkClient(base: String, account: String) {
    private val root = base.trimEnd('/') + "/s/" + URLEncoder.encode(account, "UTF-8") + "/link"

    fun status(): LinkState? = get(root)?.let { o ->
        // isNull = 키가 없거나 null. 200 인데 모양이 다르면 실패와 같게
        runCatching {
            LinkState(o.getString("state"), if (o.isNull("code")) null else o.getString("code"), o.optInt("expiresIn"),
                o.optBoolean("me"), o.optBoolean("partner"),
                if (o.isNull("incident")) null else HandoffCodec.decode(o.getString("incident")))
        }.getOrNull()
    }

    fun candidates(): List<LinkHint>? = get("$root/candidates")?.let { o ->
        runCatching {
            o.getJSONArray("near").let { a -> List(a.length()) { a.getJSONObject(it).let { h -> LinkHint(h.getInt("dt"), h.getInt("within")) } } }
        }.getOrNull()
    }

    /** 내 사고에 일회용 6자리 코드. 사고가 아직 안 올라갔으면 null */
    fun makeCode(): String? = send("code", "{}").takeIf { it.first == 200 }?.second?.optString("code")?.ifEmpty { null }

    /** 상대 코드 입력. HTTP 코드: 200 연결, 404 틀림·시간 지남, 409 사고 없음·다른 사고, 429 너무 많이 틀림, 400 내 코드, -1 연결 실패 */
    fun claim(code: String): Int = send("claim", JSONObject().put("code", code).toString()).first

    fun consent(): Boolean = send("consent", "{}").first == 200
    /** 연결 끊기·동의 철회. 상대는 더 이상 내 기록을 못 받는다 */
    fun revoke(): Boolean = send("revoke", "{}").first == 200

    private fun get(url: String): JSONObject? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        try { if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }.getOrNull()

    private fun send(op: String, body: String): Pair<Int, JSONObject?> = runCatching {
        val c = URL("$root/$op").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 3000; c.readTimeout = 5000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            code to runCatching { JSONObject((if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()) }.getOrNull()
        } finally { c.disconnect() }
    }.getOrDefault(-1 to null)
}
