package dev.seferaki.bridge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val MAX_FILE = 1073741824L
private const val MAX_TEXT = 1048576
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).hex()

data class PairInfo(val host:String, val port:Int, val pin:String, val token:String, val deviceId:String, val name:String) {
    fun json() = JSONObject().put("host",host).put("port",port).put("pin",pin).put("token",token).put("deviceId",deviceId).put("name",name)
    companion object { fun from(j:JSONObject) = PairInfo(j.getString("host"),j.getInt("port"),j.getString("pin"),j.getString("token"),j.getString("deviceId"),j.getString("name")) }
}
data class Invitation(val host:String,val port:Int,val pin:String,val secret:String,val deviceId:String,val name:String) {
    companion object {
        fun parse(value:String, manualHost:String? = null): Invitation {
            require(value.toByteArray().size <= 8192) { "Код слишком большой" }
            val obj = JSONObject(value.trim()); require(obj.getInt("version") == 1) { "Версия кода не поддерживается" }
            val h = manualHost?.trim()?.takeIf { it.isNotEmpty() } ?: obj.getJSONArray("hosts").getString(0)
            require(h.length in 1..253 && h.all { it.isLetterOrDigit() || it in ".-:" }) { "Некорректный адрес Mac" }
            val port=obj.getInt("port"); require(port in 1..65535) { "Некорректный порт" }
            val pin=obj.getString("fingerprint"); require(pin.matches(Regex("[a-f0-9]{64}"))) { "Некорректный отпечаток сертификата" }
            val secret=obj.getString("invitation"); require(secret.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Некорректный код подключения" }
            return Invitation(h,port,pin,secret,obj.getString("deviceId"),obj.getString("name"))
        }
    }
}
class PairStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("bridge_pair", Context.MODE_PRIVATE)
    private val alias = "samsung_mac_bridge_pair_v1"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore"); store.load(null)
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        val g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }
    fun save(info:PairInfo) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        val value=cipher.iv+cipher.doFinal(info.json().toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("data",Base64.encodeToString(value,Base64.NO_WRAP)).apply()
    }
    fun load():PairInfo? = try {
        val raw=Base64.decode(prefs.getString("data",null) ?: return null,Base64.NO_WRAP); require(raw.size > 28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12)))
        PairInfo.from(JSONObject(String(cipher.doFinal(raw.copyOfRange(12,raw.size)),Charsets.UTF_8)))
    } catch (_:Exception) { null }
    fun clear() { prefs.edit().remove("data").apply(); val store=KeyStore.getInstance("AndroidKeyStore"); store.load(null); if(store.containsAlias(alias)) store.deleteEntry(alias) }
}
internal class LocalClientIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_client_identity", Context.MODE_PRIVATE)
    @Synchronized fun get(): String {
        val saved = prefs.getString("clientId", null)
        if (saved != null && runCatching { java.util.UUID.fromString(saved) }.isSuccess) return saved.lowercase()
        return java.util.UUID.randomUUID().toString().lowercase().also { prefs.edit().putString("clientId", it).commit() }
    }
}
class BridgeClient(private val context:Context) {
    private val storage=PairStorage(context)
    private val clientID=LocalClientIdentity(context).get()
    @Volatile var pair:PairInfo? = storage.load()
    @Volatile var cancelRequested=false
    @Volatile private var activeConnection:HttpsURLConnection?=null
    @Volatile private var imageClipboardSupported:Boolean?=null
    var progress:((Long,Long)->Unit)? = null
    fun forget() { pair=null; storage.clear() }
    fun forgetRemote() {
        try { post("/v1/unpair", JSONObject()) }
        finally { forget() }
    }
    fun cancel() { cancelRequested=true; activeConnection?.disconnect() }
    private fun connection(host:String,port:Int,pin:String,path:String,method:String,token:String?=null):HttpsURLConnection {
        require(host.length in 1..253 && host.all { it.isLetterOrDigit() || it in ".-:" }) { "Некорректный адрес" }
        require(pin.matches(Regex("[a-f0-9]{64}"))) { "Некорректный сертификат" }
        val expected=pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun check(chain:Array<out X509Certificate>?) {
            if(chain.isNullOrEmpty()) throw CertificateException("Сертификат отсутствует")
            chain[0].checkValidity()
            if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(chain[0].encoded))) throw CertificateException("Сертификат Mac не совпадает")
        }
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?) { throw CertificateException("Client certificates not used") }
            override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?) { check(chain) }
        }
        val ssl=SSLContext.getInstance("TLS"); ssl.init(null,arrayOf(trust),SecureRandom())
        val c=URL("https",host,port,path).openConnection() as HttpsURLConnection
        c.sslSocketFactory=ssl.socketFactory
        c.hostnameVerifier=javax.net.ssl.HostnameVerifier { checkedHost,session ->
            try { checkedHost.equals(host,true) && (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { fingerprint(it)==pin } == true } catch (_:Exception) { false }
        }
        c.connectTimeout=12000; c.readTimeout=30000; c.requestMethod=method; c.setRequestProperty("Connection","close"); if(token!=null)c.setRequestProperty("Authorization","Bearer $token")
        return c
    }
    private fun readJson(c:HttpsURLConnection, maxBytes:Int=2097152):JSONObject {
        val code=c.responseCode
        val stream=if(code in 200..299)c.inputStream else c.errorStream
        val bytes=stream?.use { input ->
            val result=ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) {
                val n=input.read(buffer); if(n<0) break; if(n==0) continue
                require(result.size()+n<=maxBytes) { "Ответ слишком велик" }
                result.write(buffer,0,n)
            }
            result.toByteArray()
        } ?: ByteArray(0)
        val body=if(bytes.isNotEmpty()) JSONObject(String(bytes,Charsets.UTF_8)) else JSONObject()
        if(code !in 200..299) throw BridgeHttpException(code, body.optString("error","Ошибка соединения ($code)"))
        return body
    }
    private fun post(host:String,port:Int,pin:String,path:String,body:JSONObject,token:String?=null,maxRequestBytes:Int=2097152,maxResponseBytes:Int=2097152):JSONObject {
        val c=connection(host,port,pin,path,"POST",token)
        try { val bytes=body.toString().toByteArray(Charsets.UTF_8); require(bytes.size<=maxRequestBytes); c.doOutput=true; c.setRequestProperty("Content-Type","application/json"); c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) }; return readJson(c,maxResponseBytes) } finally { c.disconnect() }
    }
    fun pair(inv:Invitation, phoneName:String):PairInfo {
        val first=post(inv.host,inv.port,inv.pin,"/v1/pair",JSONObject().put("invitation",inv.secret).put("name",phoneName).put("clientId",clientID))
        val id=first.getString("requestId"); val deadline=System.currentTimeMillis()+115000
        while(System.currentTimeMillis()<deadline) {
            Thread.sleep(1000)
            val status=post(inv.host,inv.port,inv.pin,"/v1/pair/status",JSONObject().put("invitation",inv.secret).put("requestId",id))
            when(status.getString("status")) {
                "rejected" -> throw IOException("На Mac отклонили подключение")
                "approved" -> {
                    require(status.getString("deviceId")==inv.deviceId) { "Устройство не совпадает" }
                    val info=PairInfo(inv.host,inv.port,inv.pin,status.getString("token"),inv.deviceId,status.getString("name")); storage.save(info); pair=info; return info
                }
            }
        }
        throw IOException("Время подключения истекло")
    }
    fun changeHost(host:String) { val p=pair ?: error("Телефон не подключён"); require(host.length in 1..253 && host.all { it.isLetterOrDigit() || it in ".-:" }); pair=p.copy(host=host); storage.save(pair!!) }
    internal fun refreshPair() { pair = storage.load() }
    internal fun tryDiscoveredAddress(host:String, port:Int):Boolean {
        val original = storage.load() ?: return false
        if (port !in 1..65535) return false
        val c = connection(host,port,original.pin,"/v1/status","GET",original.token)
        try {
            if (readJson(c).getString("deviceId") != original.deviceId) return false
            val current = storage.load() ?: return false
            if (current.deviceId != original.deviceId || current.token != original.token || current.pin != original.pin) return false
            val updated = current.copy(host=host,port=port)
            storage.save(updated); pair=updated
            return true
        } finally { c.disconnect() }
    }
    private fun post(path:String,body:JSONObject):JSONObject { val p=pair ?: error("Телефон не подключён"); return post(p.host,p.port,p.pin,path,body,p.token) }
    fun status():JSONObject {
        refreshPair()
        val p=pair ?: error("Телефон не подключён"); val c=connection(p.host,p.port,p.pin,"/v1/status","GET",p.token)
        val model = AndroidDeviceIdentity.networkLabel(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        if (model.matches(Regex("[A-Za-z0-9 ._-]{1,64}"))) c.setRequestProperty("X-Bridge-Phone-Model", model)
        c.setRequestProperty("X-Bridge-Client-ID", clientID)
        try {
            val o=readJson(c)
            require(o.getString("deviceId")==p.deviceId) { "Устройство не совпадает" }
            if (o.optString("clientId") == clientID) ClipboardHistory(context).migrateTokenHistoryToPair(p,clientID)
            return o
        } finally { c.disconnect() }
    }
    fun sendText(text:String):String {
        require(text.toByteArray(Charsets.UTF_8).size in 1..MAX_TEXT) { "Текст пустой или больше 1 МиБ" }
        val type=if(text.startsWith("https://") || text.startsWith("http://")) "link" else "text"
        return post("/v1/text",JSONObject().put("type",type).put("text",text)).getString("id")
    }
    internal fun publishClipboard(payload:ClipboardPayload):String {
        refreshPair()
        require(payload.source=="android") { "На Mac можно отправить только событие телефона" }
        val p=pair ?: error("Телефон не подключён")
        if (BluetoothModePreferences(context).enabled) {
            return BluetoothBridgeRuntime.sendAndAwait(BluetoothClipboardMessage.encode(payload), payload.eventId)
                .also { require(it in setOf("accepted", "duplicate")) { "Mac не принял clipboard-событие" } }
        }
        val response=post(p.host,p.port,p.pin,"/v1/clipboard",payload.toJson(),p.token,MAX_CLIPBOARD_JSON_BYTES,2097152)
        require(response.optString("eventId")==payload.eventId) { "Mac вернул другой ID clipboard-события" }
        val state=response.optString("state")
        require(state=="accepted" || state=="duplicate") { "Mac не принял clipboard-событие" }
        return state
    }
    internal fun supportsClipboardImages():Boolean {
        if (BluetoothModePreferences(context).enabled) return BluetoothBridgeRuntime.connected
        imageClipboardSupported?.let { return it }
        refreshPair()
        val p=pair ?: error("Телефон не подключён")
        val c=connection(p.host,p.port,p.pin,"/v1/capabilities","GET",p.token)
        try {
            val code=c.responseCode
            if(code==400 || code==404) { imageClipboardSupported=false; return false }
            if(code==401) throw SecurityException("Пара отозвана или недействительна")
            val supported=readJson(c).optInt("clipboardImagesVersion",0)>=1
            imageClipboardSupported=supported
            return supported
        } finally { c.disconnect() }
    }
    internal fun publishClipboardImage(event:ClipboardImageEvent):String {
        refreshPair()
        require(event.source=="android") { "На Mac можно отправить только событие телефона" }
        val p=pair ?: error("Телефон не подключён")
        if (BluetoothModePreferences(context).enabled) {
            return BluetoothBridgeRuntime.sendAndAwait(BluetoothClipboardMessage.encode(event), event.eventId, 180_000)
                .also { require(it in setOf("accepted", "duplicate")) { "Mac не принял изображение" } }
        }
        require(supportsClipboardImages()) { "Mac не поддерживает буфер изображений" }
        val c=connection(p.host,p.port,p.pin,"/v1/clipboard/image","POST",p.token)
        activeConnection=c
        try {
            c.doOutput=true
            c.setRequestProperty("Content-Type",event.image.mimeType)
            c.setRequestProperty("X-Bridge-Event-Id",event.eventId)
            c.setRequestProperty("X-Bridge-Source",event.source)
            c.setRequestProperty("X-Bridge-SHA256",event.image.sha256)
            c.setFixedLengthStreamingMode(event.image.data.size)
            c.outputStream.use { it.write(event.image.data) }
            val response=readJson(c,2048)
            require(response.optString("eventId")==event.eventId) { "Mac вернул другой ID события изображения" }
            return response.optString("state").also { require(it=="accepted" || it=="duplicate") { "Mac не принял изображение" } }
        } catch (error:Exception) {
            if(error.message?.contains("401")==true || error.message?.contains("Нет доступа",ignoreCase=true)==true) throw SecurityException("Пара отозвана или недействительна",error)
            throw error
        } finally { activeConnection=null; c.disconnect() }
    }
    internal fun nextClipboardImage(afterEventId:String?):ClipboardImageEvent? {
        val p=pair ?: error("Телефон не подключён")
        cancelRequested=false
        val request=JSONObject().put("afterEventId",afterEventId ?: JSONObject.NULL).put("waitSeconds",25)
        val c=connection(p.host,p.port,p.pin,"/v1/clipboard/image/next","POST",p.token)
        activeConnection=c
        try {
            val bytes=request.toString().toByteArray(Charsets.UTF_8)
            c.doOutput=true; c.setRequestProperty("Content-Type","application/json"); c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code=c.responseCode
            if(code==204) return null
            if(code!=200) { readJson(c,2048); throw IOException("Не удалось получить изображение ($code)") }
            val mimeType=c.contentType?.substringBefore(';')?.trim() ?: ""
            val eventId=c.getHeaderField("X-Bridge-Event-Id") ?: ""
            val source=c.getHeaderField("X-Bridge-Source") ?: ""
            val hash=c.getHeaderField("X-Bridge-SHA256") ?: ""
            val length=c.contentLengthLong
            if(length !in 1..ClipboardImage.MAX_BYTES.toLong()) throw IOException("Недопустимый размер изображения")
            val output=ByteArrayOutputStream(length.toInt()); val buffer=ByteArray(16*1024)
            c.inputStream.use { input ->
                while(true) {
                    val count=input.read(buffer)
                    if(count<0) break
                    if(count==0) continue
                    if(output.size()+count>ClipboardImage.MAX_BYTES) throw IOException("Изображение больше 8 МиБ")
                    output.write(buffer,0,count)
                }
            }
            require(source=="mac") { "Mac вернул изображение с неверным источником" }
            val image=ClipboardImage.validate(output.toByteArray(),mimeType,hash)
            return ClipboardImageEvent.create(source,image,eventId)
        } catch(error:Exception) {
            if(error.message?.contains("401")==true || error.message?.contains("Нет доступа",ignoreCase=true)==true) throw SecurityException("Пара отозвана или недействительна",error)
            throw error
        } finally { activeConnection=null; c.disconnect() }
    }
    internal fun nextClipboardEvent(afterEventId:String?):ClipboardPayload? {
        val p=pair ?: error("Телефон не подключён")
        cancelRequested=false
        val request=JSONObject().put("afterEventId",afterEventId ?: JSONObject.NULL).put("waitSeconds",25)
        val c=connection(p.host,p.port,p.pin,"/v1/clipboard/next","POST",p.token)
        activeConnection=c
        try {
            c.doOutput=true; c.setRequestProperty("Content-Type","application/json")
            val bytes=request.toString().toByteArray(Charsets.UTF_8); c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val event=readJson(c,MAX_CLIPBOARD_JSON_BYTES).optJSONObject("event") ?: return null
            val payload=ClipboardPayload.fromJson(event)
            require(payload.source=="mac") { "Mac вернул событие с неверным источником" }
            return payload
        } catch (error:Exception) {
            if(error.message?.contains("401") == true || error.message?.contains("Нет доступа",ignoreCase=true) == true) throw SecurityException("Пара отозвана или недействительна",error)
            throw error
        } finally { activeConnection=null; c.disconnect() }
    }
    fun accept(id:String) { post("/v1/transfers/$id/accept",JSONObject()) }
    fun cancelTransfer(id:String) { cancel(); post("/v1/transfers/$id/cancel",JSONObject()) }
    internal fun requestCancellation(id:String) { post("/v1/transfers/$id/cancel",JSONObject()) }
    fun acknowledge(id:String,hash:String?=null) { val j=JSONObject(); if(hash!=null)j.put("sha256",hash); post("/v1/transfers/$id/ack",j) }
    internal fun reconcileTransfer(id:String):TransferOutcome {
        refreshPair()
        val p = pair ?: return TransferOutcome.UNKNOWN
        val c = connection(p.host,p.port,p.pin,"/v1/transfers/$id/result","GET",p.token)
        return try { transferOutcome(readJson(c).optString("state")) } finally { c.disconnect() }
    }
    fun displayName(uri:Uri):String {
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) return it.getString(0) ?: "file" }
        return "file"
    }
    data class Prepared(val uri:Uri,val name:String,val size:Long,val hash:String)
    fun prepare(uri:Uri):Prepared {
        val name=displayName(uri); require(name.isNotEmpty() && name!="." && name!=".." && name.toByteArray(Charsets.UTF_8).size<=180 && name.none { it=='/' || it=='\\' || it.isISOControl() }) { "Некорректное имя файла" }
        val md=MessageDigest.getInstance("SHA-256"); var total=0L; val buffer=ByteArray(65536); cancelRequested=false
        (context.contentResolver.openInputStream(uri) ?: throw IOException("Файл недоступен")).use { input -> while(true) { if(cancelRequested)throw IOException("Отменено"); val n=input.read(buffer); if(n<0)break; if(n==0)continue; total+=n; require(total<=MAX_FILE) { "Файл больше 1 ГиБ" }; md.update(buffer,0,n) } }
        return Prepared(uri,name,total,md.digest().hex())
    }
    fun offer(f:Prepared):String = post("/v1/offers",JSONObject().put("name",f.name).put("size",f.size).put("sha256",f.hash)).getString("id")
    fun upload(id:String,f:Prepared) {
        val p=pair ?: error("Телефон не подключён"); cancelRequested=false
        val c=connection(p.host,p.port,p.pin,"/v1/files/$id","POST",p.token); activeConnection=c
        try {
            c.doOutput=true; c.setRequestProperty("Content-Type","application/octet-stream"); c.setFixedLengthStreamingMode(f.size)
            val hash=MessageDigest.getInstance("SHA-256"); var count=0L; val buf=ByteArray(65536)
            (context.contentResolver.openInputStream(f.uri) ?: throw IOException("Файл недоступен")).use { input -> c.outputStream.use { output -> while(true) { if(cancelRequested)throw IOException("Отменено"); val n=input.read(buf); if(n<0)break; if(n==0)continue; count+=n; require(count<=f.size) { "Исходный файл изменился" }; hash.update(buf,0,n); output.write(buf,0,n); progress?.invoke(count,f.size) } } }
            require(count==f.size && hash.digest().hex()==f.hash) { "Исходный файл изменился" }; readJson(c)
        } finally { activeConnection=null; c.disconnect() }
    }
    fun download(id:String,size:Long,expectedHash:String):File {
        require(size in 0..MAX_FILE && expectedHash.matches(Regex("[a-f0-9]{64}")))
        val p=pair ?: error("Телефон не подключён"); cancelRequested=false
        val c=connection(p.host,p.port,p.pin,"/v1/files/$id","GET",p.token); activeConnection=c
        val file=File.createTempFile("bridge-", ".part",context.cacheDir)
        try {
            if(c.responseCode != 200) { readJson(c); throw IOException("Ошибка получения") }
            require(c.contentLengthLong==size && c.getHeaderField("X-Content-SHA256")==expectedHash) { "Размер файла не совпадает" }
            val md=MessageDigest.getInstance("SHA-256"); var count=0L; val buf=ByteArray(65536)
            c.inputStream.use { input -> file.outputStream().use { output -> while(true) { if(cancelRequested)throw IOException("Отменено"); val n=input.read(buf); if(n<0)break; if(n==0)continue; count+=n; require(count<=size); md.update(buf,0,n); output.write(buf,0,n); progress?.invoke(count,size) } } }
            require(count==size && md.digest().hex()==expectedHash) { "Контрольная сумма не совпадает" }; return file
        } catch(e:Exception) { file.delete(); throw e } finally { activeConnection=null; c.disconnect() }
    }

}
