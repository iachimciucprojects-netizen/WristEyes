package com.wristeyes

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/** A request as seen by a route handler. */
class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
) {
    /** Body parsed as JSON, or an empty object when there is no body. */
    val json: JSONObject by lazy { if (body.isBlank()) JSONObject() else JSONObject(body) }
}

class Response(val status: Int, val body: JSONObject) {
    companion object {
        fun ok(body: JSONObject = JSONObject().put("ok", true)) = Response(200, body)
        fun error(status: Int, message: String) =
            Response(status, JSONObject().put("ok", false).put("error", message))
    }
}

/**
 * Minimal HTTP/1.1 server: one request per connection, JSON in and out.
 * No dependencies, so the APK stays tiny. Every request except GET / needs
 * the header `Authorization: Bearer <token>`.
 */
class HttpServer(
    private val port: Int,
    private val token: String,
    private val handler: (Request) -> Response,
) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val server = ServerSocket(port)
        socket = server
        Thread({
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    pool.execute { serve(client) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                }
            }
        }, "wristeyes-http").start()
    }

    fun stop() {
        socket?.close()
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { s ->
            s.soTimeout = 15_000
            val response = try {
                val req = parse(BufferedInputStream(s.getInputStream()))
                when {
                    req == null -> Response.error(400, "bad request")
                    req.method == "GET" && req.path == "/" -> Response.ok(
                        JSONObject().put("ok", true).put("name", "WristEyes").put("auth", "Bearer token required")
                    )
                    req.headers["authorization"] != "Bearer $token" -> Response.error(401, "missing or wrong token")
                    else -> handler(req)
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
                Response.error(500, e.message ?: e.javaClass.simpleName)
            }
            write(s, response)
        }
    }

    private fun parse(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val q = target.substringAfter('?', "")
        val query = q.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
        return Request(parts[0].uppercase(), target.substringBefore('?'), query, headers, String(body, 0, read, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 8192) return null
        }
    }

    private fun write(s: Socket, r: Response) {
        val bytes = r.body.toString().toByteArray(Charsets.UTF_8)
        val reason = when (r.status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 ${r.status} $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        s.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    companion object {
        private const val TAG = "WristEyes"
        private const val MAX_BODY = 64 * 1024
    }
}
