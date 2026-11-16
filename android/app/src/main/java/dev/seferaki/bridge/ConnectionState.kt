package dev.seferaki.bridge

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException
import android.content.Context
import android.net.wifi.WifiManager

internal class BridgeHttpException(val status: Int, message: String = "Ошибка соединения") : IOException(message)

internal enum class ConnectionProblem { ACCESS_REVOKED, TRUST, UNAVAILABLE }

internal fun classifyConnectionError(error: Throwable): ConnectionProblem {
    var current: Throwable? = error
    while (current != null) {
        if (current is BridgeHttpException && current.status == 401) return ConnectionProblem.ACCESS_REVOKED
        if (current is CertificateException || current is SSLPeerUnverifiedException) return ConnectionProblem.TRUST
        current = current.cause
    }
    return ConnectionProblem.UNAVAILABLE
}

internal fun connectionMessage(problem: ConnectionProblem): String = when (problem) {
    ConnectionProblem.ACCESS_REVOKED -> "Mac отклонил доступ. Проверьте сопряжение."
    ConnectionProblem.TRUST -> "Не удалось подтвердить подлинность Mac. Проверьте сопряжение."
    ConnectionProblem.UNAVAILABLE -> "Mac недоступен. Проверьте Wi‑Fi, приложение на Mac и режим сна."
}

internal fun wifiEnabled(context: Context): Boolean =
    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled

internal fun connectionFailureMessage(context: Context, error: Throwable): String =
    if (!wifiEnabled(context)) "Wi‑Fi на телефоне выключен. Включите его и подключитесь к сети Mac."
    else connectionMessage(classifyConnectionError(error))
