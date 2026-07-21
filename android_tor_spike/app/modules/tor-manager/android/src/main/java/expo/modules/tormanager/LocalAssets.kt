package expo.modules.tormanager

import android.content.Context

/** Static asset provider for LocalWebServer, reading the bundled hearth/web
 *  bundle from `assets/www/`. Mapping: LocalWebServer passes the request path
 *  minus its leading slash ("index.html", "static/style.css", "sw.js", …).
 *  hearth mounts its bundle at /static, so a "static/" prefix maps to the
 *  bundle root; "index.html" and "sw.js" map directly. A `..` anywhere is
 *  refused (path-traversal guard) so a crafted request can never escape www/. */
object LocalAssets {
    fun provide(ctx: Context, key: String): Pair<String, ByteArray>? {
        val rel = key.removePrefix("static/")
        if (rel.contains("..") || rel.startsWith("/")) return null
        val bytes = try {
            ctx.assets.open("www/$rel").use { it.readBytes() }
        } catch (e: Exception) { return null }
        return mimeFor(rel) to bytes
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        name.endsWith(".webmanifest") -> "application/manifest+json"
        name.endsWith(".woff2") -> "font/woff2"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".ico") -> "image/x-icon"
        name.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
