package com.remotephone

import android.content.Context
import android.os.Build
import org.json.JSONObject

/** Who may connect. Read at decision time, so a change applies to the next hello. */
object Prefs {
    const val ASK = "ask_before_connect"      // the owner must allow a new computer on the phone
    const val REMEMBER = "remember_clients"   // Allow also remembers the computer for later sessions
    const val MULTIPLE = "allow_multiple"     // more than one computer at a time
    const val WEB_CLIENT = "web_client"     // serve the browser client from the phone while mirroring
    const val DESKTOP = "desktop_mode"        // stream a Shizuku-created desktop display instead of the phone screen
    private const val DESKTOP_WIDTH = "desktop_width"
    const val PHONE_NAME = "phone_name"            // shown to clients instead of the model name
    private const val DISABLED_INTERFACES = "disabled_interfaces"  // network kinds clients may not connect through

    // Desktop display: 16:9, width dragged in steps on a bar
    const val DESKTOP_MIN_WIDTH = 960
    const val DESKTOP_MAX_WIDTH = 3840
    const val DESKTOP_STEP = 160
    private const val REMEMBERED = "remembered_computers"  // JSON object: client id to display name

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun ask(ctx: Context) = prefs(ctx).getBoolean(ASK, true)
    fun remember(ctx: Context) = prefs(ctx).getBoolean(REMEMBER, false)
    fun multiple(ctx: Context) = prefs(ctx).getBoolean(MULTIPLE, false)
    fun desktop(ctx: Context) = prefs(ctx).getBoolean(DESKTOP, false)
    fun webClient(ctx: Context) = prefs(ctx).getBoolean(WEB_CLIENT, false)

    fun desktopSize(ctx: Context): Triple<Int, Int, Int> = desktopSizeFor(prefs(ctx).getInt(DESKTOP_WIDTH, 1920))

    /** 16:9 at [width] (a multiple of 160, so the height stays even); density grows past 1080p so text keeps its size. */
    fun desktopSizeFor(width: Int): Triple<Int, Int, Int> {
        val w = (width.coerceIn(DESKTOP_MIN_WIDTH, DESKTOP_MAX_WIDTH) / DESKTOP_STEP) * DESKTOP_STEP
        return Triple(w, w * 9 / 16, if (w <= 1920) 160 else w / 12)
    }

    fun setDesktopWidth(ctx: Context, width: Int) = prefs(ctx).edit().putInt(DESKTOP_WIDTH, width).apply()

    fun defaultPhoneName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    fun customPhoneName(ctx: Context): String = prefs(ctx).getString(PHONE_NAME, "") ?: ""
    fun phoneName(ctx: Context): String = customPhoneName(ctx).trim().ifEmpty { defaultPhoneName() }
    fun setPhoneName(ctx: Context, name: String) = prefs(ctx).edit().putString(PHONE_NAME, name.take(40)).apply()

    fun disabledInterfaces(ctx: Context): Set<String> = prefs(ctx).getStringSet(DISABLED_INTERFACES, emptySet())?.toSet() ?: emptySet()
    fun interfaceAllowed(ctx: Context, kind: String) = kind !in disabledInterfaces(ctx)
    fun setInterfaceAllowed(ctx: Context, kind: String, allowed: Boolean) {
        val set = disabledInterfaces(ctx).toMutableSet()
        if (allowed) set.remove(kind) else set.add(kind)
        prefs(ctx).edit().putStringSet(DISABLED_INTERFACES, set).apply()
    }
    fun set(ctx: Context, key: String, value: Boolean) = prefs(ctx).edit().putBoolean(key, value).apply()

    class Remembered(val name: String, val secret: String)

    fun remembered(ctx: Context): Map<String, Remembered> {
        val json = JSONObject(prefs(ctx).getString(REMEMBERED, null) ?: "{}")
        return json.keys().asSequence().associateWith {
            val entry = json.getJSONObject(it)
            Remembered(entry.getString("name"), entry.getString("secret"))
        }
    }
    fun addRemembered(ctx: Context, id: String, name: String, secret: String) {
        val json = JSONObject(prefs(ctx).getString(REMEMBERED, null) ?: "{}")
        json.put(id, JSONObject().put("name", name).put("secret", secret))
        prefs(ctx).edit().putString(REMEMBERED, json.toString()).apply()
    }
    fun forgetRemembered(ctx: Context) = prefs(ctx).edit().remove(REMEMBERED).apply()
}
