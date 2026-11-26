package app.olauncher.extras.iconpack

import org.xmlpull.v1.XmlPullParser

/**
 * Reads an icon pack's appfilter.xml: `<item component="ComponentInfo{pkg/cls}" drawable="name"/>`.
 * Returns component ("pkg/cls") → drawable name. Pure over an XmlPullParser, unit tested.
 */
object AppFilterParser {

    fun parse(parser: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                componentKey(component)?.let { key -> if (!drawable.isNullOrBlank()) map.putIfAbsent(key, drawable) }
            }
            event = parser.next()
        }
        return map
    }

    /** Package → a drawable of one of its components, for apps whose exact activity the pack doesn't list. */
    fun byPackage(map: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>()
        map.forEach { (component, drawable) -> out.putIfAbsent(component.substringBefore('/'), drawable) }
        return out
    }

    /** "ComponentInfo{com.app/com.app.Main}" → "com.app/com.app.Main"; null when malformed. */
    fun componentKey(raw: String?): String? {
        if (raw == null) return null
        val start = raw.indexOf('{')
        val end = raw.indexOf('}')
        if (start < 0 || end <= start + 1) return null
        val inner = raw.substring(start + 1, end).trim()
        val slash = inner.indexOf('/')
        if (slash <= 0 || slash == inner.lastIndex) return null
        val pkg = inner.substring(0, slash)
        var cls = inner.substring(slash + 1)
        if (cls.startsWith(".")) cls = pkg + cls
        return "$pkg/$cls"
    }
}
