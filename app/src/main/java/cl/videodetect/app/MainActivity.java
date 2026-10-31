package cl.videodetect.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONTokener;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(11, 16, 28);
    private static final int CARD = Color.rgb(26, 35, 53);
    private static final int LINE = Color.rgb(49, 62, 82);
    private static final int MUTED = Color.rgb(163, 178, 199);
    private static final int WHITE = Color.WHITE;
    private static final int ACCENT = Color.rgb(47, 203, 188);
    private static final String SCAN_SCRIPT = "(function(){var a=[],seen={};function add(s){if(typeof s==='string'&&s.length>5&&!seen[s]){seen[s]=1;a.push(s)}}document.querySelectorAll('video,source,audio,iframe[src],a[href],*[data-video],*[data-file],*[data-hls],*[data-src]').forEach(function(n){add(n.currentSrc);add(n.src);var h=n.getAttribute('href');if(h&&(/\\\\.(mp4|webm|m3u8|mpd)([?#]|$)/i.test(h)))add(h);['data-src','data-video','data-file','data-hls'].forEach(function(k){add(n.getAttribute(k))})});try{performance.getEntriesByType('resource').forEach(function(r){add(r.name)})}catch(e){}return JSON.stringify(a)})()";
    private final Map<String,Media> media = new LinkedHashMap<>();
    private final Set<String> ads = new HashSet<>(Arrays.asList("doubleclick.net","googlesyndication.com","googleadservices.com","popads.net","popcash.net","exoclick.com","exosrv.com","adsterra.com","onclickads.net","propellerads.com","trafficjunky.net","juicyads.com","adskeeper.co.uk"));
    private final List<Long> downloadIds = new ArrayList<>();
    private final android.os.Handler scanner = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService inspectors=java.util.concurrent.Executors.newFixedThreadPool(2);
    private final Set<String> inspected = java.util.Collections.synchronizedSet(new HashSet<>());
    private String lastPageUrl="";
    private WebView web;
    private AdBlockEngine blocker;
    private MediaDiagnostics diagnostics;
    private TextView protectionBadge;
    private EditText address;
    private LinearLayout root, content, bottom;
    private int tab = 0;
    private boolean opened = false;
    private int downloadsStarted = 0;

    private static class Media {
        final String url, type, quality, filename, host;
        boolean selected = false;
        Media(String url, String type) {
            this.url = url; this.type = type;
            Uri uri = Uri.parse(url);
            String path = uri.getLastPathSegment();
            filename = "Reproductor".equals(type) ? "Reproductor incrustado" : path == null || path.trim().isEmpty() ? "video" : Uri.decode(path);
            host = uri.getHost() == null ? "Sitio web" : uri.getHost();
            Matcher m = Pattern.compile("(?i)(?:^|[^0-9])(2160|1440|1080|720|480|360|240)p(?:[^0-9]|$)").matcher(url);
            quality = "Reproductor".equals(type) ? "Abrir y reproducir" : m.find() ? m.group(1) + "p" : "Sin indicar";
        }
    }
    private int d(float n) { return (int)(getResources().getDisplayMetrics().density*n + 0.5f); }
    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(d(radius));
        if (stroke != 0) bg.setStroke(d(1), stroke);
        return bg;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextColor(color); t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(1); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(0); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void gap(LinearLayout l, int height) { View v = new View(this); l.addView(v, new LinearLayout.LayoutParams(1,d(height))); }
    private TextView button(String label, boolean primary, Runnable action) {
        TextView b = text(label, 14, primary ? BG : WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(d(13),d(12),d(13),d(12));
        b.setBackground(rounded(primary ? ACCENT : CARD, 12, primary ? 0 : LINE));
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private void spaceRow(LinearLayout row, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1);
        p.setMargins(0,0,d(8),0); row.addView(child,p);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        SharedPreferences pref=getSharedPreferences("downloads", MODE_PRIVATE);
        String ids=pref.getString("ids","");
        for(String part:ids.split(","))try {downloadIds.add(Long.parseLong(part));}catch(Exception ignored){}
        root=vertical();root.setBackgroundColor(BG);setContentView(root);
        blocker=new AdBlockEngine(this, () -> {if(protectionBadge!=null)refreshProtectionBadge();});
        LinearLayout header=horizontal();
        header.setPadding(d(18),d(14),d(18),d(12));
        LinearLayout title=vertical();
        title.addView(text("VideoDetect",23,WHITE,true));
        title.addView(text("Navega  •  encuentra  •  descarga",12,MUTED,false));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        protectionBadge=text("●  Bloqueo activo",11,ACCENT,true);
        protectionBadge.setPadding(d(10),d(9),d(10),d(9));protectionBadge.setBackground(rounded(CARD,30,LINE));
        protectionBadge.setOnClickListener(v->showProtectionDialog());
        header.addView(protectionBadge); root.addView(header);
        refreshProtectionBadge();
        View divider=new View(this);divider.setBackgroundColor(LINE);
        root.addView(divider,new LinearLayout.LayoutParams(-1,d(1)));
        content=vertical();root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        bottom=horizontal();bottom.setPadding(d(10),d(8),d(10),d(8));
        bottom.setBackgroundColor(Color.rgb(18, 25, 39));root.addView(bottom);

        web=new WebView(this);
        web.setBackgroundColor(WHITE);
        diagnostics=new MediaDiagnostics(web, url -> discover(url));
        diagnostics.install();
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)
            WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView v,boolean dialog,boolean gesture,Message m){return false;}
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req) {
                Uri uri=req.getUrl();
                diagnostics.recordNetwork(uri.toString(),req.isForMainFrame());
                if(blocker.blocks(uri.toString()))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                discover(uri.toString());
                return super.shouldInterceptRequest(v,req);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req) {
                Uri uri=req.getUrl();
                if(blocker.blocks(uri.toString()))return true;
                String scheme=uri.getScheme();
                if(!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)))return true;
                // Block unwanted top-level popunders from the known troublesome page.
                if(blocker.isEnabled() && req.isForMainFrame() && !req.hasGesture() && web.getUrl()!=null){
                    String oldHost=Uri.parse(web.getUrl()).getHost();
                    String nextHost=uri.getHost();
                    if(oldHost!=null && nextHost!=null && oldHost.endsWith("pervtube.net")
                         && !nextHost.equals(oldHost) && !nextHost.endsWith(".pervtube.net"))
                        return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView v,String url) {
                if(tab==0 && address!=null)address.setText(url);
                applyPageFilters();
                diagnostics.onPageFinished(url);
                scan(false);
                scanner.postDelayed(()->{if(web!=null && opened && url.equals(web.getUrl()))scan(false);},1300);
                scanner.postDelayed(()->{if(web!=null && opened && url.equals(web.getUrl()))scan(false);},4000);
            }
        });
        web.setDownloadListener((url,userAgent,disposition,mime,len) -> {
            discover(url); if(tab==0)confirmDownload(url);
        });
        show(0);
    }
    private boolean isAd(String host) {
        if(host==null)return false;
        host=host.toLowerCase(Locale.ROOT);
        for(String ad:ads)if(host.equals(ad)||host.endsWith("."+ad))return true;
        return false;
    }
    private String typeOf(String url) {
        if(url==null)return null;
        String path=url.split("[?#]",2)[0].toLowerCase(Locale.ROOT);
        if(path.matches(".*\\.(mp4|m4v)(/)?$"))return "MP4";
        if(path.matches(".*\\.webm(/)?$"))return "WebM";
        if(path.matches(".*\\.m3u8(/)?$"))return "HLS";
        if(path.matches(".*\\.mpd(/)?$"))return "DASH";
        if(path.matches(".*\\.(mp3|m4a|aac|wav|flac)(/)?$"))return "Audio";
        Uri uri=Uri.parse(url);
        String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        // Known embed host from the HTML sample. This is a player URL, not an MP4.
        if(host.equals("moviekh.xtremestream.xyz") && path.contains("/player/index.php"))return "Reproductor";
        if(host.equals("player.vimeo.com") && path.contains("/video/"))return "Reproductor";
        if(host.equals("www.youtube-nocookie.com") && path.contains("/embed/"))return "Reproductor";
        return null;
    }
    private void discover(String url) {
        if(url==null||!url.startsWith("http"))return;
        String kind=typeOf(url);if(kind==null)return;
        runOnUiThread(() -> {
            if(!media.containsKey(url)){
                media.put(url,new Media(url,kind));
                if("Reproductor".equals(kind))inspectPlayer(url);
                else if("HLS".equals(kind))inspectHls(url);
            }
        });
    }
    private void scan(boolean notify) {
        if(!opened)return;
        web.evaluateJavascript(SCAN_SCRIPT, result -> {
            try {
                Object decoded=new JSONTokener(result).nextValue();
                JSONArray array=new JSONArray(decoded.toString());
                for(int i=0;i<array.length();i++){
                    String candidate=array.optString(i,"");
                    if(candidate.startsWith("blob:")||candidate.startsWith("data:"))continue;
                    try {
                        String resolved=new URL(new URL(web.getUrl()),candidate).toString();
                        discover(resolved);
                    }catch(Exception ignored){}
                }
            } catch(Exception ignored){}
            if(notify){Toast.makeText(this,"Detección: "+media.size()+" fuentes encontradas.",Toast.LENGTH_SHORT).show();show(1);}
        });
    }
    private void show(int selected) {
        tab=selected;content.removeAllViews();bottom.removeAllViews();
        String[] names={"Navegador","Videos ("+(media.size()+diagnostics.streamGroups().size())+")","Descargas","Diagnóstico"};
        for(int i=0;i<4;i++){
            final int target=i;
            TextView item=text(names[i],11,i==tab?BG:MUTED,i==tab);
            item.setGravity(Gravity.CENTER);
            item.setPadding(d(6),d(15),d(6),d(15));
            item.setBackground(rounded(i==tab?ACCENT:Color.TRANSPARENT,12,0));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
            p.setMargins(d(3),0,d(3),0);bottom.addView(item,p);
            item.setOnClickListener(v->show(target));
        }
        if(tab==0)showBrowser();else if(tab==1)showMedia();else if(tab==2)showDownloads();else showDiagnostics();
    }
    private void showBrowser() {
        LinearLayout top=vertical();top.setPadding(d(14),d(14),d(14),d(12));
        TextView label=text("DIRECCIÓN DEL SITIO WEB",11,MUTED,true);top.addView(label);
        gap(top,7);
        LinearLayout bar=horizontal();
        address=new EditText(this);
        address.setSingleLine(true);address.setTextSize(14);
        address.setTextColor(WHITE);address.setHintTextColor(MUTED);
        address.setHint("Pega una URL o escribe una búsqueda");
        address.setPadding(d(13),0,d(12),0);
        address.setBackground(rounded(CARD,12,LINE));
        address.setText(opened && web.getUrl()!=null ? web.getUrl() : "");
        bar.addView(address,new LinearLayout.LayoutParams(0,d(48),1));
        TextView go=button("IR",true,()->navigate());
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(d(62),d(48));
        gp.setMargins(d(8),0,0,0);bar.addView(go,gp);
        address.setOnEditorActionListener((v,id,event)->{navigate();return true;});
        top.addView(bar);gap(top,10);
        LinearLayout controls=horizontal();
        spaceRow(controls,button("← Atrás",false,()->{if(web.canGoBack())web.goBack();}));
        spaceRow(controls,button("↻ Recargar",false,()->{if(opened)web.reload();}));
        controls.addView(button("◎ Detectar videos",true,()->scan(true)),new LinearLayout.LayoutParams(0,-2,1.4f));
        top.addView(controls);
        content.addView(top);
        if(!opened){
            ScrollView scroll=new ScrollView(this);LinearLayout intro=vertical();
            intro.setPadding(d(18),d(24),d(18),d(22));
            TextView welcome=text("Tu navegador de videos",24,WHITE,true);intro.addView(welcome);gap(intro,8);
            intro.addView(text("Abre una página y reproduce el video que quieres descargar.",15,MUTED,false));
            gap(intro,22);
            LinearLayout card=vertical();card.setBackground(rounded(CARD,18,LINE));card.setPadding(d(18),d(20),d(18),d(18));
            card.addView(text("CÓMO USAR LA APLICACIÓN",12,ACCENT,true));gap(card,16);
            card.addView(text("1   Escribe o pega la dirección de la página.",15,WHITE,false));gap(card,16);
            card.addView(text("2   Inicia la reproducción del video.",15,WHITE,false));gap(card,16);
            card.addView(text("3   Pulsa «Detectar videos».",15,WHITE,false));gap(card,16);
            card.addView(text("4   En Videos, elige la calidad disponible y descárgala.",15,WHITE,false));
            intro.addView(card);gap(intro,18);
            intro.addView(text("Puedes ver el progreso de las descargas en la pestaña Descargas. Las fuentes protegidas o no expuestas por el navegador pueden no detectarse.",13,MUTED,false));
            scroll.addView(intro);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        } else {
            if(web.getParent()!=null)((ViewGroup)web.getParent()).removeView(web);
            content.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        }
    }

    private void refreshProtectionBadge(){
        if(protectionBadge==null||blocker==null)return;
        protectionBadge.setText(blocker.isEnabled()?"●  Filtros activos":"○  Sin filtros");
        protectionBadge.setTextColor(blocker.isEnabled()?ACCENT:MUTED);
    }
    private void showProtectionDialog(){
        if(blocker==null)return;
        String message="Protección de anuncios y ventanas emergentes.\n\n"
          +blocker.getState()+"\n"
          +"Reglas de red: "+blocker.getLoadedCount()+"\n"
          +"Solicitudes bloqueadas: "+blocker.getBlockedCount()+"\n\n"
          +"EasyList, EasyPrivacy y AdGuard Popups se actualizan por Internet y se guardan para funcionar sin conexión. Se aplica un subconjunto compatible de sus reglas.\n\n"
          +"Si el video no reproduce, puedes desactivar temporalmente los filtros aquí.";
        new AlertDialog.Builder(this).setTitle("Protección de publicidad")
          .setMessage(message)
          .setPositiveButton(blocker.isEnabled()?"Desactivar":"Activar",(d,w)->{
              blocker.setEnabled(!blocker.isEnabled());
              refreshProtectionBadge();
              if(opened){web.reload();}
          })
          .setNeutralButton("Actualizar filtros",(d,w)->{
              blocker.refresh();
              Toast.makeText(this,"Actualizando las listas de filtros",Toast.LENGTH_LONG).show();
          })
          .setNegativeButton("Cerrar",null).show();
    }
    private void applyPageFilters(){
        if(blocker==null || !blocker.isEnabled() || !opened)return;
        String host="";
        try{host=Uri.parse(web.getUrl()).getHost();}catch(Exception ignored){}
        final String css=blocker.cosmeticCss(host);
        final String cleanScript=
              "(function(){try{"
            + "var style=document.getElementById('videodetect-filter-css');"
            + "if(!style){style=document.createElement('style');style.id='videodetect-filter-css';(document.head||document.documentElement).appendChild(style);}"
            + "style.textContent="+org.json.JSONObject.quote(css)+";"
            + "if(!window.__vdAdCleaner){window.__vdAdCleaner=true;"
            + "var run=function(){document.querySelectorAll('div,aside,section').forEach(function(el){"
            + "if(el===document.body||el===document.documentElement||el.querySelector('video'))return;"
            + "var t=(el.innerText||'').toLowerCase().slice(0,250);"
            + "if(t.indexOf('you have (')<0&&t.indexOf('new video')<0&&t.indexOf('obtener bonificaci')<0&&t.indexOf('¡date prisa!')<0&&t.indexOf('reclama tu bono')<0)return;"
            + "var st=getComputedStyle(el),r=el.getBoundingClientRect();"
            + "if(r.width<80||r.height<30)return;"
            + "if(st.position==='fixed'||st.position==='sticky'||(parseInt(st.zIndex,10)||0)>900){el.style.setProperty('display','none','important');}"
            + "});};"
            + "run();var timer=0;"
            + "new MutationObserver(function(){if(timer)return;timer=setTimeout(function(){timer=0;run();},200);}).observe(document.documentElement,{childList:true,subtree:true});"
            + "}"
            + "}catch(e){}})()";
        web.evaluateJavascript(cleanScript,null);
    }

    private void navigate() {
        String url=address.getText().toString().trim();
        if(url.isEmpty()){Toast.makeText(this,"Introduce una dirección",Toast.LENGTH_SHORT).show();return;}
        if(!url.matches("(?i)^https?://.*")){
            if(url.contains(".")&&!url.contains(" "))url="https://"+url;
            else url="https://www.google.com/search?q="+Uri.encode(url);
        }
        opened=true;web.loadUrl(url);show(0);
    }
    private void showMedia() {
        ScrollView scroll=new ScrollView(this);LinearLayout holder=vertical();
        holder.setPadding(d(16),d(18),d(16),d(18));
        holder.addView(text("Videos y reproductores",23,WHITE,true));gap(holder,6);
        int ready=0, players=0;
        for(Media m:media.values()){if("Reproductor".equals(m.type))players++;else ready++;}
        holder.addView(text(ready+" archivos / transmisiones detectados · "+players+" reproductores incrustados. Las resoluciones se muestran solo cuando están indicadas.",13,MUTED,false));gap(holder,15);
        LinearLayout options=horizontal();
        spaceRow(options,button("Seleccionar todos",false,()->{for(Media m:media.values())if(!"Reproductor".equals(m.type))m.selected=true;show(1);}));
        spaceRow(options,button("Limpiar lista",false,()->{media.clear();show(1);}));
        options.addView(button("Buscar más",true,()->show(0)),new LinearLayout.LayoutParams(0,-2,1));
        holder.addView(options);gap(holder,14);
        java.util.List<MediaDiagnostics.StreamGroup> detectedStreams=diagnostics.streamGroups();
        if(!detectedStreams.isEmpty()){
            holder.addView(text("TRANSMISIONES SEGMENTADAS",13,ACCENT,true));
            gap(holder,8);
            for(MediaDiagnostics.StreamGroup group:detectedStreams){
                LinearLayout streamCard=vertical();
                streamCard.setPadding(d(16),d(17),d(16),d(17));
                streamCard.setBackground(rounded(CARD,16,LINE));
                streamCard.addView(text("Transmisión detectada · Calidad indicada: "+group.quality,16,WHITE,true));
                gap(streamCard,7);
                streamCard.addView(text(group.fragments+" fragmentos observados · "+group.hosts+" servidores CDN",13,ACCENT,false));
                gap(streamCard,7);
                streamCard.addView(text("Ruta con sufijo '"+group.suffix+"'. No se ha identificado un MP4 completo ni confirmado un método de descarga. Estos fragmentos no son archivos descargables individuales.",13,MUTED,false));
                gap(streamCard,11);
                streamCard.addView(button("Ver diagnóstico",false,()->show(3)));
                holder.addView(streamCard);gap(holder,12);
            }
        }
        if(media.isEmpty()&&detectedStreams.isEmpty()){
            LinearLayout empty=vertical();empty.setBackground(rounded(CARD,16,LINE));empty.setPadding(d(20),d(24),d(20),d(24));
            empty.addView(text("Todavía no se detectaron fuentes",19,WHITE,true));gap(empty,10);
            empty.addView(text("1. Abre la pestaña Navegador.\n2. Reproduce un video en la página.\n3. Pulsa Detectar videos.\n\nAquí aparecerán los formatos y las distintas resoluciones encontradas.",15,MUTED,false));
            holder.addView(empty);
        }
        for(Media item:new ArrayList<>(media.values())){
            LinearLayout card=vertical();card.setBackground(rounded(CARD,16,LINE));card.setPadding(d(13),d(13),d(13),d(13));
            LinearLayout first=horizontal();
            TextView check=text(item.selected?"☑":"☐",27,ACCENT,true);
            first.addView(check,new LinearLayout.LayoutParams(d(40),d(44)));
            LinearLayout details=vertical();
            details.addView(text(item.type+"   •   "+displayQuality(item),14,ACCENT,true));
            TextView name=text(item.filename,15,WHITE,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(name);
            TextView domain=text(item.host,12,MUTED,false);details.addView(domain);
            first.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            first.setOnClickListener(v->{item.selected=!item.selected;check.setText(item.selected?"☑":"☐");});
            card.addView(first);gap(card,12);
            LinearLayout row=horizontal();
            spaceRow(row,button("Copiar enlace",false,()->copy(item.url)));
            row.addView(button("Reproductor".equals(item.type) ? "▶ Abrir reproductor" : "↓ Descargar",true,
                ()->{if("Reproductor".equals(item.type))openPlayer(item.url);else confirmDownload(item.url);}),
                new LinearLayout.LayoutParams(0,-2,1));
            card.addView(row);
            holder.addView(card);gap(holder,10);
        }
        gap(holder,65);
        scroll.addView(holder);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(!media.isEmpty()){
            TextView selected=button("↓ Descargar seleccionados",true,()->downloadSelected());
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,d(52));p.setMargins(d(16),d(8),d(16),d(12));
            content.addView(selected,p);
        }
    }
    private void downloadSelected(){
        int count=0, unsupported=0;
        for(Media m:media.values())if(m.selected){
            if(m.type.equals("HLS")||m.type.equals("DASH")||m.type.equals("Reproductor"))unsupported++;
            else {enqueue(m.url);count++;}
        }
        if(count==0 && unsupported==0){Toast.makeText(this,"Selecciona al menos un archivo",Toast.LENGTH_SHORT).show();return;}
        if(unsupported>0)new AlertDialog.Builder(this).setTitle("Formatos de transmisión")
            .setMessage("HLS y DASH están detectados, pero esta versión todavía no convierte esos formatos en archivos de video. Los archivos MP4/WebM seleccionados sí se enviaron al gestor.")
            .setPositiveButton("Entendido",null).show();
        if(count>0)show(2);
    }
    private void confirmDownload(String url) {
        String type=typeOf(url);
        if("Reproductor".equals(type)){openPlayer(url);return;}
        if(type==null||type.equals("HLS")||type.equals("DASH")){
            new AlertDialog.Builder(this).setTitle("Transmisión detectada")
                .setMessage("HLS/DASH requiere procesar una lista de segmentos. Esta versión no lo descarga todavía. Puedes copiar la dirección del recurso.")
                .setPositiveButton("Copiar URL",(a,b)->copy(url)).setNegativeButton("Cerrar",null).show();
            return;
        }
        String filename=Uri.parse(url).getLastPathSegment();
        new AlertDialog.Builder(this).setTitle("Descargar archivo")
            .setMessage((filename==null?"Video":filename)+"\n\nFormato: "+type)
            .setPositiveButton("Descargar",(a,b)->{enqueue(url);show(2);})
            .setNegativeButton("Cancelar",null).show();
    }
    private void openPlayer(String url) {
        new AlertDialog.Builder(this).setTitle("Reproductor encontrado")
          .setMessage("Este enlace abre el reproductor del video, pero todavía no es un archivo MP4. Al abrirlo, pulsa Play y regresa a Videos para revisar si aparecen las fuentes de descarga.")
          .setNegativeButton("Cerrar",null)
          .setPositiveButton("Abrir reproductor",(dialog,which)->{
              opened=true;
              String referer=web.getUrl();
              show(0);
              if(referer!=null&&referer.startsWith("http")){
                  java.util.Map<String,String> headers=new java.util.HashMap<>();
                  headers.put("Referer",referer);
                  web.loadUrl(url,headers);
              }else web.loadUrl(url);
          }).show();
    }

    private void inspectPlayer(String url) {
        if(!inspected.add("player:"+url))return;
        inspectors.execute(()->{
            String html=fetchSmallText(url);
            if(html!=null){
                collectMediaUrls(html,url);
            }
        });
    }
    private void inspectHls(String url) {
        if(!inspected.add("hls:"+url))return;
        inspectors.execute(()->{
            String playlist=fetchSmallText(url);
            if(playlist==null||!playlist.contains("#EXTM3U"))return;
            String[] lines=playlist.replace("\\r","").split("\\n");
            for(int i=0;i<lines.length;i++){
                if(!lines[i].startsWith("#EXT-X-STREAM-INF:"))continue;
                String info=lines[i];
                String quality="";
                java.util.regex.Matcher match=java.util.regex.Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(info);
                if(match.find())quality=match.group(1)+"p";
                int j=i+1;
                while(j<lines.length&&lines[j].trim().isEmpty())j++;
                if(j>=lines.length||lines[j].startsWith("#"))continue;
                try {
                    final String variant=new URL(new URL(url),lines[j].trim()).toString();
                    final String label=quality;
                    runOnUiThread(()->addHlsVariant(variant,label));
                }catch(Exception ignored){}
            }
        });
    }
    private void addHlsVariant(String url,String quality){
        if(media.containsKey(url))return;
        Media m=new Media(url,"HLS");
        media.put(url,m);
        playlistLabels.put(url,quality);
    }
    private final Map<String,String> playlistLabels = new java.util.HashMap<>();
    private String displayQuality(Media item){
        String custom=playlistLabels.get(item.url);
        return custom==null||custom.isEmpty()?item.quality:custom;
    }
    private String fetchSmallText(String url){
        java.net.HttpURLConnection connection=null;
        try{
            URL target=new URL(url);
            if(!"https".equalsIgnoreCase(target.getProtocol()))return null;
            connection=(java.net.HttpURLConnection)target.openConnection();
            connection.setConnectTimeout(6500);connection.setReadTimeout(8500);
            connection.setRequestProperty("User-Agent",web.getSettings().getUserAgentString());
            String cookie=CookieManager.getInstance().getCookie(url);
            if(cookie!=null)connection.setRequestProperty("Cookie",cookie);
            String ref=web.getUrl();
            if(ref!=null&&ref.startsWith("https://"))connection.setRequestProperty("Referer",ref);
            if(connection.getResponseCode()!=200)return null;
            try(java.io.InputStream in=connection.getInputStream();
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n,total=0;
                while((n=in.read(buf))!=-1){total+=n;if(total>900000)return null;out.write(buf,0,n);}
                return out.toString("UTF-8");
            }
        }catch(Exception e){return null;}
        finally{if(connection!=null)connection.disconnect();}
    }
    private void collectMediaUrls(String html,String base){
        // Only parse literal URLs in player markup; never execute embedded scripts.
        String decoded=html.replace("\\\\/","/").replace("&amp;","&");
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile(
            "(?i)(?:https?:)?//[^\\s\\\"'<>\\\\]{5,600}?\\.(?:mp4|m4v|webm|m3u8|mpd)(?:\\?[^\\s\\\"'<>\\\\]{0,250})?"
        ).matcher(decoded);
        int count=0;
        while(matcher.find()&&count++<40){
            String candidate=matcher.group();
            if(candidate.startsWith("//"))candidate="https:"+candidate;
            discover(candidate);
        }
    }

    private void enqueue(String url) {
        try {
            String type=typeOf(url);
            String ext="MP4".equals(type)?"mp4":"WebM".equals(type)?"webm":"mp3";
            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
            req.setTitle("VideoDetect · "+ext.toUpperCase(Locale.ROOT));
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "VideoDetect_"+System.currentTimeMillis()+"_"+(++downloadsStarted)+"."+ext);
            String cookies=CookieManager.getInstance().getCookie(url);
            if(cookies!=null&&!cookies.isEmpty())req.addRequestHeader("Cookie",cookies);
            req.addRequestHeader("User-Agent",web.getSettings().getUserAgentString());
            if(web.getUrl()!=null)req.addRequestHeader("Referer",web.getUrl());
            long id=((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            downloadIds.add(id);saveDownloadIds();
            Toast.makeText(this,"Descarga añadida a la cola",Toast.LENGTH_SHORT).show();
        }catch(Exception ex){
            new AlertDialog.Builder(this).setTitle("No se pudo descargar")
                .setMessage(ex.getMessage()).setPositiveButton("Cerrar",null).show();
        }
    }
    private void saveDownloadIds(){
        StringBuilder b=new StringBuilder();
        for(Long id:downloadIds){if(b.length()>0)b.append(",");b.append(id);}
        getSharedPreferences("downloads",MODE_PRIVATE).edit().putString("ids",b.toString()).apply();
    }
    private void showDownloads() {
        ScrollView scroll=new ScrollView(this);LinearLayout holder=vertical();
        holder.setPadding(d(16),d(18),d(16),d(16));
        holder.addView(text("Mis descargas",23,WHITE,true));gap(holder,7);
        holder.addView(text("Consulta el estado de los archivos enviados al gestor de Android.",13,MUTED,false));gap(holder,15);
        LinearLayout actions=horizontal();
        spaceRow(actions,button("↻ Actualizar",true,()->show(2)));
        actions.addView(button("Abrir Descargas",false,()->{
            try{startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));}
            catch(Exception e){Toast.makeText(this,"No se pudo abrir Descargas",Toast.LENGTH_LONG).show();}
        }),new LinearLayout.LayoutParams(0,-2,1));
        holder.addView(actions);gap(holder,16);
        if(downloadIds.isEmpty()){
            LinearLayout card=vertical();card.setPadding(d(20),d(24),d(20),d(24));
            card.setBackground(rounded(CARD,16,LINE));
            card.addView(text("Aún no has descargado archivos",18,WHITE,true));gap(card,9);
            card.addView(text("Después de detectar un video, selecciónalo en la pestaña Videos y pulsa Descargar.",14,MUTED,false));
            holder.addView(card);
        }
        DownloadManager mgr=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        for(int i=downloadIds.size()-1;i>=0;i--){
            long id=downloadIds.get(i);String status="No disponible";int percentage=-1;
            Cursor c=null;
            try{
                c=mgr.query(new DownloadManager.Query().setFilterById(id));
                if(c!=null&&c.moveToFirst()){
                    int s=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long now=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if(total>0)percentage=(int)(100*now/total);
                    if(s==DownloadManager.STATUS_SUCCESSFUL)status="Completada";
                    else if(s==DownloadManager.STATUS_FAILED)status="Fallida";
                    else if(s==DownloadManager.STATUS_RUNNING)status="Descargando";
                    else if(s==DownloadManager.STATUS_PAUSED)status="Pausada";
                    else status="En cola";
                }
            }catch(Exception ignored){}finally{if(c!=null)c.close();}
            LinearLayout card=vertical();card.setPadding(d(16),d(14),d(16),d(14));
            card.setBackground(rounded(CARD,14,LINE));
            card.addView(text("Descarga #"+id,15,WHITE,true));gap(card,5);
            card.addView(text(status+(percentage>=0?"  ·  "+percentage+"%":""),13,status.equals("Completada")?ACCENT:MUTED,false));
            holder.addView(card);gap(holder,10);
        }
        scroll.addView(holder);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }

    private void showDiagnostics() {
        ScrollView scroll=new ScrollView(this);
        LinearLayout area=vertical();
        area.setPadding(d(16),d(17),d(16),d(18));
        area.addView(text("Diagnóstico de video",23,WHITE,true));
        gap(area,8);
        area.addView(text("Registra reproductores, fragmentos, tipos HTTP y actividad de buffers de audio/video. No guarda el contenido multimedia.",14,MUTED,false));
        gap(area,18);
        LinearLayout instructions=vertical();
        instructions.setPadding(d(16),d(16),d(16),d(16));
        instructions.setBackground(rounded(CARD,14,LINE));
        instructions.addView(text("PRUEBA RECOMENDADA",13,ACCENT,true));
        gap(instructions,10);
        instructions.addView(text("1. Abre la página desde Navegador.\n2. Intenta reproducir el video durante 15 segundos.\n3. Vuelve a Diagnóstico y pulsa «Actualizar».\n4. Pulsa «Compartir informe» y envíame el texto.",15,WHITE,false));
        area.addView(instructions);
        gap(area,14);
        area.addView(text(diagnostics.getStatus(),13,ACCENT,true));
        gap(area,10);
        LinearLayout buttons=horizontal();
        spaceRow(buttons,button("↻ Actualizar",false,()->show(3)));
        spaceRow(buttons,button("Limpiar",false,()->{diagnostics.clear();show(3);}));
        buttons.addView(button("Compartir informe",true,()->shareDiagnosis()),new LinearLayout.LayoutParams(0,-2,1.5f));
        area.addView(buttons);
        gap(area,14);
        area.addView(text("EVENTOS OBSERVADOS · "+diagnostics.size(),12,MUTED,true));
        gap(area,8);
        String visible=diagnostics.preview();
        TextView log=text(visible.isEmpty()?"Todavía no hay eventos. Abre una página e intenta reproducir un video.":visible,12,WHITE,false);
        log.setTextIsSelectable(true);
        log.setPadding(d(13),d(13),d(13),d(13));
        log.setBackground(rounded(CARD,12,LINE));
        area.addView(log);
        gap(area,16);
        area.addView(text("Privacidad: el informe elimina parámetros de consulta, que pueden contener claves temporales o identificadores. Revisa el texto antes de compartirlo. No registra contraseñas ni contenido de formularios.",12,MUTED,false));
        scroll.addView(area);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void shareDiagnosis(){
        String report=diagnostics.report(web==null?"":web.getUrl());
        new AlertDialog.Builder(this).setTitle("Compartir diagnóstico")
          .setMessage("El reporte no contiene parámetros de consulta de los enlaces. Revisa igualmente la información del sitio antes de enviarla.")
          .setNegativeButton("Cancelar",null)
          .setNeutralButton("Copiar texto",(dialog,which)->copy(report))
          .setPositiveButton("Compartir",(dialog,which)->{
              Intent send=new Intent(Intent.ACTION_SEND);
              send.setType("text/plain");
              send.putExtra(Intent.EXTRA_SUBJECT,"VideoDetect - Diagnóstico multimedia");
              send.putExtra(Intent.EXTRA_TEXT,report);
              startActivity(Intent.createChooser(send,"Compartir diagnóstico"));
          }).show();
    }

    private void copy(String s) {
        ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("Enlace multimedia",s));
        Toast.makeText(this,"Enlace copiado",Toast.LENGTH_SHORT).show();
    }
    @Override public void onBackPressed(){
        if(tab!=0){show(0);return;}
        if(web.canGoBack())web.goBack();else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(web!=null){if(web.getParent()!=null)((ViewGroup)web.getParent()).removeView(web);web.destroy();}
        inspectors.shutdownNow();scanner.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
