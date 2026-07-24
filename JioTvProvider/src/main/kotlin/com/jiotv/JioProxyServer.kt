package com.jiotv

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import com.lagradost.cloudstream3.app
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

object JioProxyServer {
    var isRunning = false
    private var server: ProxyServer? = null
    lateinit var context: Context

    fun startServer(ctx: Context) {
        context = ctx
        server = ProxyServer(8080)
        server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        isRunning = true
        Log.i("JioTV", "Local proxy server started on port 8080")
    }

    class ProxyServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val params = session.parameters

            try {
                when {
                    uri == "/" || uri == "/login" -> return handleLoginHtml()
                    uri == "/send_otp" -> return handleSendOtp(params["number"]?.firstOrNull())
                    uri == "/verify_otp" -> return handleVerifyOtp(params["number"]?.firstOrNull(), params["otp"]?.firstOrNull())
                    uri == "/play.m3u8" -> return handlePlayM3u8(params["id"]?.firstOrNull())
                    uri == "/stream.m3u8" -> return handleStreamM3u8(params["id"]?.firstOrNull(), params["cid"]?.firstOrNull(), params["ck"]?.firstOrNull())
                    uri == "/auth" -> return handleAuth(params["ck"]?.firstOrNull(), params["ts"]?.firstOrNull(), params["pkey"]?.firstOrNull())
                    else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                }
            } catch (e: Exception) {
                Log.e("JioTV", "Proxy Error", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }

        private fun getJioHeaders(cookie: String = ""): Map<String, String> {
            val prefs = context.getSharedPreferences("JioTV_Auth", Context.MODE_PRIVATE)
            val authJson = prefs.getString("jio_cred", "{}")
            val cred = JSONObject(authJson)
            val user = cred.optJSONObject("sessionAttributes")?.optJSONObject("user") ?: JSONObject()

            val headers = mutableMapOf(
                "accesstoken" to cred.optString("authToken", ""),
                "appkey" to "NzNiMDhlYzQyNjJm",
                "channel_id" to "144",
                "crmid" to user.optString("subscriberId", ""),
                "deviceId" to cred.optString("deviceId", ""),
                "devicetype" to "phone",
                "isott" to "true",
                "languageId" to "6",
                "lbcookie" to "1",
                "os" to "android",
                "osVersion" to "14",
                "srno" to "250918144000",
                "ssotoken" to cred.optString("ssoToken", ""),
                "subscriberid" to user.optString("subscriberId", ""),
                "uniqueId" to user.optString("unique", ""),
                "User-Agent" to "plaYtv/7.1.3 (Linux;Android 14) ExoPlayerLib/2.11.7",
                "usergroup" to "tvYR7NSNn7rymo3F",
                "versionCode" to "452",
                "Origin" to "https://www.jiocinema.com",
                "Referer" to "https://www.jiocinema.com/"
            )
            if (cookie.isNotEmpty()) {
                headers["Cookie"] = cookie
            }
            return headers
        }

        private fun makeSyncRequest(url: String, headers: Map<String, String>): okhttp3.Response {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { reqBuilder.addHeader(it.key, it.value) }
            return app.baseClient.newCall(reqBuilder.build()).execute()
        }

        private fun makeSyncPost(url: String, payload: String, headers: Map<String, String> = emptyMap()): okhttp3.Response {
            val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
            val reqBuilder = Request.Builder().url(url).post(body)
            headers.forEach { reqBuilder.addHeader(it.key, it.value) }
            return app.baseClient.newCall(reqBuilder.build()).execute()
        }

        private fun handleLoginHtml(): Response {
            val html = """
                <html>
                <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body style="font-family: sans-serif; text-align: center; padding: 20px;">
                    <h2>JioTV Login</h2>
                    <div id="step1">
                        <input id="num" type="text" placeholder="Jio Mobile Number" style="padding: 10px; font-size: 16px;"><br><br>
                        <button onclick="sendOtp()" style="padding: 10px 20px; font-size: 16px; background: #007bff; color: #fff; border: none; border-radius: 5px;">Send OTP</button>
                    </div>
                    <div id="step2" style="display:none;">
                        <input id="otp" type="text" placeholder="Enter OTP" style="padding: 10px; font-size: 16px;"><br><br>
                        <button onclick="verifyOtp()" style="padding: 10px 20px; font-size: 16px; background: #28a745; color: #fff; border: none; border-radius: 5px;">Verify OTP</button>
                    </div>
                    <p id="msg" style="color: red;"></p>
                    <script>
                        let number = "";
                        function sendOtp() {
                            number = document.getElementById("num").value;
                            fetch("/send_otp?number=" + number).then(r=>r.text()).then(t=>{
                                if(t==="OK") {
                                    document.getElementById("step1").style.display="none";
                                    document.getElementById("step2").style.display="block";
                                    document.getElementById("msg").innerText="OTP Sent!";
                                } else {
                                    document.getElementById("msg").innerText=t;
                                }
                            });
                        }
                        function verifyOtp() {
                            let otp = document.getElementById("otp").value;
                            fetch("/verify_otp?number=" + number + "&otp=" + otp).then(r=>r.text()).then(t=>{
                                if(t==="OK") {
                                    document.getElementById("step2").style.display="none";
                                    document.getElementById("msg").style.color="green";
                                    document.getElementById("msg").innerText="Login Successful! You can close this page and return to CloudStream.";
                                } else {
                                    document.getElementById("msg").innerText=t;
                                }
                            });
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun handleSendOtp(number: String?): Response {
            if (number.isNullOrEmpty()) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing number")
            val payload = """{"number":"$number"}"""
            val headers = mapOf(
                "appName" to "RJIL_JioTv",
                "os" to "android"
            )
            val res = makeSyncPost("https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/send", payload, headers)
            return if (res.isSuccessful) newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            else newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, res.body?.string() ?: "Failed")
        }

        private fun handleVerifyOtp(number: String?, otp: String?): Response {
            if (number.isNullOrEmpty() || otp.isNullOrEmpty()) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing params")
            val payload = """{"number":"$number","otp":"$otp","deviceInfo":{"consumptionDeviceName":"phone","info":{"type":"android","platform":{"name":"android","version":"11"},"androidId":"1234567890abcdef"}}}"""
            val headers = mapOf(
                "appName" to "RJIL_JioTv",
                "os" to "android"
            )
            val res = makeSyncPost("https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/verify", payload, headers)
            val body = res.body?.string() ?: ""
            if (res.isSuccessful && body.contains("ssoToken")) {
                context.getSharedPreferences("JioTV_Auth", Context.MODE_PRIVATE).edit().putString("jio_cred", body).apply()
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, body)
        }

        private fun handlePlayM3u8(id: String?): Response {
            if (id == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing id")
            
            val payload = "channel_id=$id&stream_type=Seek"
            val res = makeSyncPost("https://jiotvapi.media.jio.com/playback/apis/v1/geturl?langId=6", payload, getJioHeaders())
            val body = res.body?.string() ?: ""
            val json = try { JSONObject(body) } catch(e: Exception) { return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Auth failed or token expired: $body") }
            
            val resultUrl = json.optString("result", "")
            if (resultUrl.isEmpty()) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No stream url: $body")
            
            val parts = resultUrl.split("?", limit = 2)
            val baseUrl = parts[0]
            val query = if (parts.size > 1) parts[1] else ""
            
            val ck = android.util.Base64.encodeToString(query.toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).replace("=", "")
            
            val headers1 = mapOf("User-Agent" to "plaYtv/7.1.3 (Linux;Android 14) ExoPlayerLib/2.11.7")
            val m3u8Res = makeSyncRequest(resultUrl, headers1)
            var playlist = m3u8Res.body?.string() ?: ""
            
            val chs = baseUrl.split("/")
            
            playlist = playlist.replace("jiotvmblive.cdn.jio.com", "127.0.0.1:8080/stream.m3u8?ck=$ck&ts=")
            playlist = playlist.replace("jiotvbpkmob.cdn.jio.com", "127.0.0.1:8080/stream.m3u8?ck=$ck&ts=")
            playlist = playlist.replace("jiotv.cdn.jio.com", "127.0.0.1:8080/stream.m3u8?ck=$ck&ts=")
            
            if ("bpk-tv" in query) {
                val chs4 = if(chs.size > 4) chs[4] else ""
                playlist = playlist.replace("URI=\"", "URI=\"http://127.0.0.1:8080/stream.m3u8?cid=$id&id=")
                playlist = playlist.replace("$chs4-video", "http://127.0.0.1:8080/stream.m3u8?cid=$id&id=$chs4-video")
                playlist = playlist.replace("$chs4-audio", "http://127.0.0.1:8080/stream.m3u8?cid=$id&id=$chs4-audio")
                playlist = playlist.replace("URI=\"http://127.0.0.1:8080/stream.m3u8?cid=$id&id=http://127.0.0.1:8080/stream.m3u8?cid=$id&id=", "URI=\"http://127.0.0.1:8080/stream.m3u8?cid=$id&id=")
                playlist = playlist.replace("http://127.0.0.1:8080/stream.m3u8?cid=$id&id=keyframes/http://127.0.0.1:8080/stream.m3u8?cid=$id&id=", "http://127.0.0.1:8080/stream.m3u8?cid=$id&id=keyframes/")
                playlist = playlist.replace("http://127.0.0.1:8080/stream.m3u8?cid=", "http://127.0.0.1:8080/stream.m3u8?ck=$ck&cid=")
            } else if ("/HLS/" in query) {
                // Ignore HLS parsing for now or handle appropriately
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", playlist)
        }

        private fun handleStreamM3u8(id: String?, cid: String?, ck: String?): Response {
            if (id == null || cid == null || ck == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing params")
            val chs = id.split("-")
            var ckDecoded = ""
            try {
                // Fallback to normal string decoding
                ckDecoded = String(android.util.Base64.decode(ck, android.util.Base64.URL_SAFE))
            } catch(e: Exception) {}
            
            val url = "https://jiotvmblive.cdn.jio.com/bpk-tv/${chs[0]}/Fallback/$id"
            val hsRes = makeSyncRequest(url, getJioHeaders(ckDecoded))
            var playlist = hsRes.body?.string() ?: ""
            
            // Rewrite keys and chunks
            playlist = playlist.replace(",URI=\"https://tv.media.jio.com/fallback/bpk-tv/", ",URI=\"http://127.0.0.1:8080/auth?ck=$ck&pkey=")
            playlist = playlist.replace("${chs[0]}-", "http://127.0.0.1:8080/auth?ck=$ck&ts=bpk-tv/${chs[0]}/Fallback/${chs[0]}-")
            
            return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", playlist)
        }

        private fun handleAuth(ck: String?, ts: String?, pkey: String?): Response {
            if (ck == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing ck")
            var ckDecoded = ""
            try {
                ckDecoded = String(android.util.Base64.decode(ck, android.util.Base64.URL_SAFE))
            } catch(e: Exception) {}
            
            if (pkey != null) {
                val url = "https://tv.media.jio.com/fallback/bpk-tv/$pkey"
                val res = makeSyncRequest(url, getJioHeaders(ckDecoded))
                val bytes = res.body?.bytes() ?: ByteArray(0)
                return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            
            if (ts != null) {
                val url = "https://jiotvmblive.cdn.jio.com/$ts?$ckDecoded"
                val res = makeSyncRequest(url, getJioHeaders(ckDecoded))
                val bytes = res.body?.bytes() ?: ByteArray(0)
                return newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing ts or pkey")
        }
    }
}
