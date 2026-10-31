package cl.videodetect.app;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebView;
import android.os.Handler;
import android.os.Looper;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.JavaScriptReplyProxy;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

public final class MediaDiagnostics {
    private final WebView web;
    private final Consumer<String> found;
    private final MseCaptureManager capture;
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private final Set<String> seen=new HashSet<>();
    // An unrelated advertisement iframe must not receive capture session data.
    private final Set<String> mseOrigins=java.util.Collections.synchronizedSet(new HashSet<>());
    private final Set<String> qualityCandidates=new java.util.LinkedHashSet<>();
    private final Set<String> qualityHints=new java.util.LinkedHashSet<>();
    public synchronized Set<String> qualityHints(){
        return new java.util.LinkedHashSet<>(qualityHints);
    }
    public synchronized Set<String> qualityCandidates(){
        return new java.util.LinkedHashSet<>(qualityCandidates);
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean frameSupported=false;
    private boolean scriptSupported=false;
    private String js="";
    private int mediaHits=0;
    private int segmentHits=0;
    private int mseBuffers=0;
    private int encryptedSignals=0;
    private int fetchResponses=0;
    private int mseAppendSamples=0;
    private final Set<String> observedResponses=new java.util.LinkedHashSet<>();
    private final Set<String> renderedSizes=new java.util.LinkedHashSet<>();
    private final Set<String> observedCodecs=new java.util.LinkedHashSet<>();
    private static final Pattern CHUNK_PATH=Pattern.compile(
      "^/cdn/down/([0-9a-f]{32})/([0-9]{3,4})(?:_([a-z0-9]+))?/([0-9]+)\\.html$",
      Pattern.CASE_INSENSITIVE);
    private final Map<String,ObservedStream> streams=new LinkedHashMap<>();
    private static final class ObservedStream {
       String contentId,quality,folderSuffix;
       final Set<String> parts=new HashSet<>();
       final Set<String> hosts=new HashSet<>();
       ObservedStream(String id,String q,String suffix){contentId=id;quality=q;folderSuffix=suffix;}
    }
    public static final class StreamGroup {
       public final String contentId,quality,suffix;
       public final int fragments,hosts;
       StreamGroup(String id,String quality,String suffix,int fragments,int hosts){
           this.contentId=id;this.quality=quality;this.suffix=suffix;
           this.fragments=fragments;this.hosts=hosts;
       }
    }
    public synchronized List<StreamGroup> streamGroups(){
        List<StreamGroup> out=new ArrayList<>();
        for(ObservedStream stream:streams.values()){
            out.add(new StreamGroup(stream.contentId,stream.quality,stream.folderSuffix,
                    stream.parts.size(),stream.hosts.size()));
        }
        return out;
    }
    private boolean observeFragment(String url){
        if(url==null||url.length()>2800)return false;
        try{
            Uri u=Uri.parse(url);
            String scheme=u.getScheme();
            if(!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))return false;
            String path=u.getPath();
            if(path==null)return false;
            Matcher match=CHUNK_PATH.matcher(path);
            if(!match.matches())return false;
            String domain=u.getHost();
            if(domain==null||!domain.matches("(?i)^[a-z0-9.-]*xspcdn[0-9]+\\.click$"))return false;
            String contentId=match.group(1).toLowerCase(Locale.ROOT);
            String quality=match.group(2);
            String suffix=match.group(3)==null?"":match.group(3).toLowerCase(Locale.ROOT);
            String part=match.group(4);
            String key=contentId+"|"+quality+"|"+suffix;
            synchronized(this){
                ObservedStream group=streams.get(key);
                if(group==null){
                    if(streams.size()>=20)return true;
                    group=new ObservedStream(contentId,quality,suffix);
                    streams.put(key,group);
                }
                if(group.parts.add(part)){segmentHits++;mediaHits++;}
                group.hosts.add(domain.toLowerCase(Locale.ROOT));
            }
            return true;
        }catch(Exception ignored){return false;}
    }
    private int frames=0;
    private int nativeHits=0;
    private int errors=0;
    private static final int MAX_EVENTS=300;

    public MediaDiagnostics(WebView web,Consumer<String> consumer,MseCaptureManager capture){
        this.web=web;this.found=consumer;this.capture=capture;
        try{
            InputStream in=web.getContext().getAssets().open("media_monitor.js");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            in.close(); js=out.toString("UTF-8");
        }catch(Exception e){event("SISTEMA","","No se pudo cargar el observador");}
    }
    public void install(){
        try{
            // JS can only SEND a bounded string to the app. It cannot invoke
            // downloads, read local files, execute native code or receive secrets.
            if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){
                WebViewCompat.addWebMessageListener(web,"VideoDetectMonitor",
                    Collections.singleton("*"),
                    new WebViewCompat.WebMessageListener(){
                        @Override public void onPostMessage(WebView view,WebMessageCompat message,
                           Uri sourceOrigin,boolean isMainFrame,JavaScriptReplyProxy replyProxy){
                           if(message.getType()!=WebMessageCompat.TYPE_STRING)return;
                           String body=message.getData();
                           if(body==null)return;
                           // IMPORTANT: capture-chunk contains 12 KiB of binary data
                           // encoded as ~16 KiB of Base64. The legacy 3,500-char
                           // diagnostic limit silently discarded EVERY chunk.
                           // Strictly bound capture messages separately.
                           if(body.length()>3500){
                               if(body.length()>21000 || !body.startsWith("{\"kind\":\"capture-chunk\""))return;
                               try{
                                   if(capture!=null && sourceOrigin!=null &&
                                           mseOrigins.contains(sourceOrigin.toString()))
                                       capture.onMessage(new JSONObject(body),sourceOrigin,replyProxy);
                               }catch(Exception ignored){}
                               return;
                           }
                           try{
                               JSONObject obj=new JSONObject(body);
                               String kind=obj.optString("kind","");
                               if(kind.equals("capture-poll")||kind.equals("capture-chunk")){
                                   if(capture!=null && sourceOrigin!=null &&
                                           mseOrigins.contains(sourceOrigin.toString()))
                                       capture.onMessage(obj,sourceOrigin,replyProxy);
                                   return;
                               }
                               if(!kind.matches("frame|iframe|source|resource|fetch|fetch-response|fetch-failed|mse-append|capture-sync|capture-state|capture-queued|capture-init|capture-ready|capture-wait|capture-integrity|capture-restart|capture-seek|capture-zero|capture-start-failed|quality-offered|quality-hint|quality-attempt|xhr|play|metadata|resize|mse|encrypted|media-error|js-error"))return;
                               String value=obj.optString("url","");
                               String detail=obj.optString("detail","");
                               if(value.length()>2100||detail.length()>120)return;
                               String origin=sourceOrigin==null?"":sourceOrigin.toString();
                               event(kind.toUpperCase(Locale.ROOT),value,
                                  (isMainFrame?"principal":"iframe")+" "+origin+" "+detail);
                               if("frame".equals(kind))frames++;
                               if("quality-offered".equals(kind)&&detail.matches("(240|360|480|720|1080|1440|2160)p")){
                                   synchronized(this){qualityCandidates.add(detail);}
                               }
                               if("quality-hint".equals(kind)&&detail.matches("(240|360|480|720|1080|1440|2160)p")){
                                   synchronized(this){qualityHints.add(detail);}
                               }
                               if(capture!=null&&(kind.equals("capture-zero")||kind.equals("capture-seek")||
                                     kind.equals("capture-restart")||kind.equals("capture-start-failed"))&&
                                     sourceOrigin!=null&&"moviekh.xtremestream.xyz".equalsIgnoreCase(sourceOrigin.getHost())){
                                   capture.onStartSignal(kind,detail);
                               }
                               if("capture-integrity".equals(kind)&&capture!=null
                                       && sourceOrigin!=null && mseOrigins.contains(sourceOrigin.toString())){
                                   capture.onIntegrityEvent(detail);
                               }
                               if(capture!=null && (kind.equals("capture-ready")||kind.equals("capture-wait"))){
                                   if(sourceOrigin!=null && "moviekh.xtremestream.xyz".equalsIgnoreCase(sourceOrigin.getHost()))
                                       capture.onQualitySignal(kind,detail);
                               }
                               if("mse".equals(kind)){
                                   if(sourceOrigin!=null)mseOrigins.add(sourceOrigin.toString());
                                   mseBuffers++;
                                   if(observedCodecs.size()<20)observedCodecs.add(detail);
                               }
                               if("encrypted".equals(kind))encryptedSignals++;
                               if("fetch-response".equals(kind)){
                                   fetchResponses++;
                                   if(observedResponses.size()<12)observedResponses.add(detail);
                               }
                               if("mse-append".equals(kind))mseAppendSamples++;
                               if(("resize".equals(kind)||"metadata".equals(kind))&&detail.matches("[0-9]{1,5}x[0-9]{1,5}")){
                                   if(renderedSizes.size()<30)renderedSizes.add(detail);
                                   if(capture!=null)capture.onVideoResolution(detail);
                               }
                               if("media-error".equals(kind)||"js-error".equals(kind))errors++;
                               if(!observeFragment(value) && looksMedia(value)){mediaHits++;found.accept(value);}
                           }catch(Exception ignored){}
                        }
                    });
                frameSupported=true;
            }
        }catch(Exception e){event("SISTEMA","","WebMessage no disponible: "+e.getClass().getSimpleName());}
        try{
            if(!js.isEmpty() && frameSupported &&
               WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)){
                WebViewCompat.addDocumentStartJavaScript(web,js,Collections.singleton("*"));
                scriptSupported=true;
            }
        }catch(Exception e){event("SISTEMA","","Inicio de documento no disponible");}
        event("SISTEMA","","Monitor de marcos: "+(scriptSupported?"activo":"limitado a página principal"));
    }
    public void onPageFinished(String url){
        event("PÁGINA",url,"terminó la carga");
        if(!scriptSupported&&!js.isEmpty()){
            web.evaluateJavascript(js,null);
        }
    }
    public void recordNetwork(String url,boolean isMain){
        if(!observeFragment(url) && !looksMedia(url)&&!looksPlayer(url))return;
        synchronized(this){nativeHits++;}
        event("SOLICITUD",url,isMain?"documento":"recurso / iframe");
    }
    private static boolean looksMedia(String url){
        if(url==null)return false;
        String low=url.toLowerCase(Locale.ROOT);
        return low.matches("(?s).*\\.(mp4|m4v|webm|m3u8|mpd|mp3|m4a)([/#?]|$).*")
              ||low.contains("/get_file/");
    }
    private static boolean looksPlayer(String url){
        if(url==null)return false;
        String low=url.toLowerCase(Locale.ROOT);
        return low.contains("/player/index.php")||low.contains("/embed/");
    }
    public void clear(){ synchronized(this){events.clear();seen.clear();mediaHits=0;segmentHits=0;streams.clear();qualityCandidates.clear();qualityHints.clear();frames=0;nativeHits=0;errors=0;
           mseBuffers=0;encryptedSignals=0;renderedSizes.clear();observedCodecs.clear();
           fetchResponses=0;mseAppendSamples=0;observedResponses.clear();} }
    private void event(String type,String url,String detail){
        String safe=safeUrl(url);
        String line=type+" | "+safe+" | "+shorten(detail,140);
        synchronized(this){
            if(!seen.add(line))return;
            while(events.size()>=MAX_EVENTS)events.removeFirst();
            events.addLast(line);
        }
    }
    private static String shorten(String s,int limit){
        if(s==null)return "";
        String cleaned=s.replace('\n',' ').replace('\r',' ').replace('\t',' ');
        return cleaned.length()>limit?cleaned.substring(0,limit)+"…":cleaned;
    }
    private static String safeUrl(String url){
        if(url==null||url.trim().isEmpty())return "";
        try{
            Uri u=Uri.parse(url);
            if(!"https".equalsIgnoreCase(u.getScheme())&&!"http".equalsIgnoreCase(u.getScheme()))return shorten(u.getScheme()+":",40);
            // Never export query strings or fragments, which can hold access tokens.
            Uri clean=u.buildUpon().encodedQuery(null).fragment(null).build();
            return shorten(clean.toString(),260);
        }catch(Exception e){return "[URL omitida]";}
    }
    public synchronized int size(){return events.size();}
    public synchronized String getStatus(){
        return "Solicitudes "+nativeHits+" · Eventos multimedia "+mediaHits+
          " · Fragmentos "+segmentHits+" · Marcos "+frames+" · Errores "+errors+
          "\nMSE: "+mseBuffers+" buffer(s) · Señales EME: "+encryptedSignals+
          "\nRespuestas CDN: "+fetchResponses+" · Muestras appendBuffer: "+mseAppendSamples+
          "\nCabeceras observadas: "+observedResponses+
          "\nResoluciones observadas: "+renderedSizes+
          "\nCalidades ofrecidas en controles: "+qualityCandidates+
          "\nIndicios de calidad en configuración: "+qualityHints+
          "\nCodecs MSE: "+observedCodecs+
          "\nMonitor de iframes: "+(scriptSupported?"activo":"no disponible en este WebView");
    }
    public synchronized String preview(){
        StringBuilder out=new StringBuilder();
        int skip=Math.max(0,events.size()-45),i=0;
        for(String e:events){if(i++<skip)continue;out.append("• ").append(e).append("\n\n");}
        return out.toString().trim();
    }
    public synchronized String report(String current){
        StringBuilder out=new StringBuilder();
        out.append("VideoDetect - Diagnóstico de medios\n")
           .append("Versión: 0.14 (prueba)\n")
           .append("Android: ").append(android.os.Build.VERSION.RELEASE).append("\n")
           .append("Sitio: ").append(safeUrl(current)).append("\n")
           .append("Monitor de iframe: ").append(scriptSupported?"sí":"no").append("\n")
           .append(getStatus()).append("\n\n")
           .append("Transmisiones segmentadas reconocidas:\n");
        if(streams.isEmpty())out.append("Ninguna\n");
        for(StreamGroup stream:streamGroups()){
            out.append("ID: ").append(stream.contentId)
               .append(" | Calidad indicada: ").append(stream.quality)
               .append(" | Carpeta: ").append(stream.suffix.isEmpty()?"sin sufijo":stream.suffix)
               .append(" | Fragmentos: ").append(stream.fragments)
               .append(" | CDN: ").append(stream.hosts)
               .append("\n");
        }
        out.append("\nLas consultas de URL fueron eliminadas.\n");
        for(String e:events)out.append(e).append("\n");
        return out.toString();
    }
}
