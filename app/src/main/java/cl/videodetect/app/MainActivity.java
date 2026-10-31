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
    private static final String SCAN_SCRIPT = "(function(){var a=[];function add(x){if(x&&typeof x==='string')a.push(x)}document.querySelectorAll('video,source,audio').forEach(function(n){add(n.currentSrc);add(n.src);add(n.getAttribute('data-src'))});document.querySelectorAll('[data-video],[data-file],[data-hls]').forEach(function(n){['data-video','data-file','data-hls'].forEach(function(k){add(n.getAttribute(k))})});return JSON.stringify(a)})()";
    private final Map<String,Media> media = new LinkedHashMap<>();
    private final Set<String> ads = new HashSet<>(Arrays.asList("doubleclick.net","googlesyndication.com","googleadservices.com","popads.net","popcash.net","exoclick.com","exosrv.com","adsterra.com","onclickads.net","propellerads.com","trafficjunky.net","juicyads.com","adskeeper.co.uk"));
    private final List<Long> downloadIds = new ArrayList<>();
    private WebView web;
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
            filename = path == null || path.trim().isEmpty() ? "video" : Uri.decode(path);
            host = uri.getHost() == null ? "Sitio web" : uri.getHost();
            Matcher m = Pattern.compile("(?i)(?:^|[^0-9])(2160|1440|1080|720|480|360|240)p(?:[^0-9]|$)").matcher(url);
            quality = m.find() ? m.group(1) + "p" : "Sin indicar";
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
        LinearLayout header=horizontal();
        header.setPadding(d(18),d(14),d(18),d(12));
        LinearLayout title=vertical();
        title.addView(text("VideoDetect",23,WHITE,true));
        title.addView(text("Navega  •  encuentra  •  descarga",12,MUTED,false));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView shield=text("●  Bloqueo activo",11,ACCENT,true);
        shield.setPadding(d(10),d(9),d(10),d(9));shield.setBackground(rounded(CARD,30,LINE));
        header.addView(shield); root.addView(header);
        View divider=new View(this);divider.setBackgroundColor(LINE);
        root.addView(divider,new LinearLayout.LayoutParams(-1,d(1)));
        content=vertical();root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        bottom=horizontal();bottom.setPadding(d(10),d(8),d(10),d(8));
        bottom.setBackgroundColor(Color.rgb(18, 25, 39));root.addView(bottom);

        web=new WebView(this);
        web.setBackgroundColor(WHITE);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView v,boolean dialog,boolean gesture,Message m){return false;}
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req) {
                Uri uri=req.getUrl();
                if(isAd(uri.getHost()))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                discover(uri.toString());
                return super.shouldInterceptRequest(v,req);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req) {
                Uri uri=req.getUrl();
                if(isAd(uri.getHost()))return true;
                String scheme=uri.getScheme();
                return !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
            }
            @Override public void onPageFinished(WebView v,String url) {
                if(tab==0 && address!=null)address.setText(url);
                scan(false);
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
        if(path.matches(".*\\.(mp4|m4v)$"))return "MP4";
        if(path.endsWith(".webm"))return "WebM";
        if(path.endsWith(".m3u8"))return "HLS";
        if(path.endsWith(".mpd"))return "DASH";
        if(path.matches(".*\\.(mp3|m4a|aac|wav|flac)$"))return "Audio";
        return null;
    }
    private void discover(String url) {
        if(url==null||!url.startsWith("http"))return;
        String kind=typeOf(url);if(kind==null)return;
        runOnUiThread(() -> { if(!media.containsKey(url))media.put(url,new Media(url,kind)); });
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
            if(notify){Toast.makeText(this,"Escaneo terminado: "+media.size()+" archivos.",Toast.LENGTH_SHORT).show();show(1);}
        });
    }
    private void show(int selected) {
        tab=selected;content.removeAllViews();bottom.removeAllViews();
        String[] names={"Navegador","Videos ("+media.size()+")","Descargas"};
        for(int i=0;i<3;i++){
            final int target=i;
            TextView item=text(names[i],13,i==tab?BG:MUTED,i==tab);
            item.setGravity(Gravity.CENTER);
            item.setPadding(d(6),d(15),d(6),d(15));
            item.setBackground(rounded(i==tab?ACCENT:Color.TRANSPARENT,12,0));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
            p.setMargins(d(3),0,d(3),0);bottom.addView(item,p);
            item.setOnClickListener(v->show(target));
        }
        if(tab==0)showBrowser();else if(tab==1)showMedia();else showDownloads();
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
        holder.addView(text("Archivos detectados",23,WHITE,true));gap(holder,6);
        holder.addView(text("Encontrados durante la navegación: "+media.size()+". Se muestra la resolución solo si el enlace la indica.",13,MUTED,false));gap(holder,15);
        LinearLayout options=horizontal();
        spaceRow(options,button("Seleccionar todos",false,()->{for(Media m:media.values())m.selected=true;show(1);}));
        spaceRow(options,button("Limpiar lista",false,()->{media.clear();show(1);}));
        options.addView(button("Buscar más",true,()->show(0)),new LinearLayout.LayoutParams(0,-2,1));
        holder.addView(options);gap(holder,14);
        if(media.isEmpty()){
            LinearLayout empty=vertical();empty.setBackground(rounded(CARD,16,LINE));empty.setPadding(d(20),d(24),d(20),d(24));
            empty.addView(text("Todavía no hay videos",19,WHITE,true));gap(empty,10);
            empty.addView(text("1. Abre la pestaña Navegador.\n2. Reproduce un video en la página.\n3. Pulsa Detectar videos.\n\nAquí aparecerán los formatos y las distintas resoluciones encontradas.",15,MUTED,false));
            holder.addView(empty);
        }
        for(Media item:new ArrayList<>(media.values())){
            LinearLayout card=vertical();card.setBackground(rounded(CARD,16,LINE));card.setPadding(d(13),d(13),d(13),d(13));
            LinearLayout first=horizontal();
            TextView check=text(item.selected?"☑":"☐",27,ACCENT,true);
            first.addView(check,new LinearLayout.LayoutParams(d(40),d(44)));
            LinearLayout details=vertical();
            details.addView(text(item.type+"   •   "+item.quality,14,ACCENT,true));
            TextView name=text(item.filename,15,WHITE,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(name);
            TextView domain=text(item.host,12,MUTED,false);details.addView(domain);
            first.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            first.setOnClickListener(v->{item.selected=!item.selected;check.setText(item.selected?"☑":"☐");});
            card.addView(first);gap(card,12);
            LinearLayout row=horizontal();
            spaceRow(row,button("Copiar enlace",false,()->copy(item.url)));
            row.addView(button("↓ Descargar",true,()->confirmDownload(item.url)),new LinearLayout.LayoutParams(0,-2,1));
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
            if(m.type.equals("HLS")||m.type.equals("DASH"))unsupported++;
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
        super.onDestroy();
    }
}
