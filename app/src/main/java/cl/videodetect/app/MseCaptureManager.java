package cl.videodetect.app;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.webkit.JavaScriptReplyProxy;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * User-initiated recording of regular, playable fMP4 MSE buffers.
 * Does NOT download protected resources, access keys, bypass DRM or
 * decode site-specific encrypted CDN fragments.
 * Audio and video tracks are written in arrival order and remuxed offline.
 */
public final class MseCaptureManager {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService disk=Executors.newSingleThreadScheduledExecutor();
    private final Runnable onChange;
    private volatile boolean capturing=false,closing=false,exporting=false;
    private volatile String id="",status="Captura detenida";
    private volatile String lastResult="";
    private volatile Uri savedUri;
    private File dir,audioFile,videoFile;
    private FileOutputStream aout,vout;
    private volatile long audioBytes=0,videoBytes=0;
    private final AtomicLong lastWrite=new AtomicLong();
    private ScheduledFuture<?> scheduledFinish;
    private static final long MAX_TOTAL_BYTES=1_500_000_000L;
    private static final int MAX_CHUNK_BASE64=26000;
    private volatile boolean stoppedPermanently=false;
    private int errorCount=0;

    public MseCaptureManager(Context ctx,Runnable onChange){
        this.context=ctx.getApplicationContext();this.onChange=onChange;
    }
    private void update(){if(onChange!=null)main.post(onChange);}
    public boolean isCapturing(){return capturing;}
    public boolean isBusy(){return capturing||closing||exporting;}
    public String getStatus(){return status;}
    public String getStats(){return String.format(java.util.Locale.ROOT,
        "Audio: %.1f MB · Video: %.1f MB",audioBytes/1048576.0,videoBytes/1048576.0);}
    public Uri getSavedUri(){return savedUri;}
    public String getLastResult(){return lastResult;}
    public synchronized boolean start(){
        if(isBusy()||stoppedPermanently)return false;
        id=UUID.randomUUID().toString();
        capturing=true;closing=false;exporting=false;
        savedUri=null;lastResult="";errorCount=0;
        audioBytes=0;videoBytes=0;
        dir=new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),"mse-"+id);
        audioFile=new File(dir,"audio.fmp4");
        videoFile=new File(dir,"video.fmp4");
        try {
            if(!dir.exists()&&!dir.mkdirs())throw new IOException("Sin espacio para crear carpeta");
            aout=new FileOutputStream(audioFile);
            vout=new FileOutputStream(videoFile);
        }catch(Exception e){
            capturing=false;closing=false;
            closeOutputs();
            status="No se pudo iniciar: "+e.getMessage();update();
            return false;
        }
        status="Capturando: reproduce el video desde el inicio, sin cambiar de calidad";
        lastWrite.set(System.currentTimeMillis());
        update();return true;
    }
    public synchronized void stop(){
        if(!capturing)return;
        capturing=false;closing=true;
        status="Terminando captura y preparando MP4…";
        update();
        // Allow an in-flight appendBuffer to finish sending all its chunks.
        scheduledFinish=disk.schedule(this::finishOnDisk,7,TimeUnit.SECONDS);
    }
    private synchronized void rescheduleFinish(){
        if(!closing||exporting)return;
        if(scheduledFinish!=null)scheduledFinish.cancel(false);
        scheduledFinish=disk.schedule(this::finishOnDisk,3500,TimeUnit.MILLISECONDS);
    }
    public void onMessage(JSONObject obj,Uri origin,JavaScriptReplyProxy reply){
        String kind=obj.optString("kind","");
        if("capture-poll".equals(kind)){
            replyState(reply);
            return;
        }
        if(!"capture-chunk".equals(kind))return;
        String session=obj.optString("session","");
        String media=obj.optString("track","");
        String payload=obj.optString("base64","");
        int sequence=obj.optInt("seq",-1);
        boolean allowed=media.equals("audio")||media.equals("video");
        String scheme=origin==null?null:origin.getScheme();
        if(!"https".equalsIgnoreCase(scheme)||!allowed||sequence<1||
          !session.equals(id)||!(capturing||closing)||
          payload.isEmpty()||payload.length()>MAX_CHUNK_BASE64){
            replyAck(reply,session,sequence,false);
            return;
        }
        // All disk IO stays off the WebView UI thread. ACK provides backpressure:
        // one 12KiB chunk at a time is held by JavaScript until persisted.
        disk.execute(()->{
            boolean okay=false;
            try{
                byte[] bytes=Base64.decode(payload,Base64.NO_WRAP);
                if(bytes.length>0&&bytes.length<=18000){
                    synchronized(this){
                        if(audioBytes+videoBytes+bytes.length>MAX_TOTAL_BYTES)throw new IOException("Límite de almacenamiento de 1,5 GB");
                        FileOutputStream target="audio".equals(media)?aout:vout;
                        if(target==null)throw new IOException("La captura ya está cerrada");
                        target.write(bytes);
                        if("audio".equals(media))audioBytes+=bytes.length;
                        else videoBytes+=bytes.length;
                    }
                    okay=true;lastWrite.set(System.currentTimeMillis());
                    if((audioBytes+videoBytes)%(1024*1024)<bytes.length)update();
                }
            }catch(Exception e){
                errorCount++;
                if(errorCount==1){status="Error al guardar datos: "+e.getMessage();update();}
            }
            final boolean acknowledged=okay;
            main.post(()->replyAck(reply,session,sequence,acknowledged));
            if(acknowledged)rescheduleFinish();
        });
    }
    private void replyState(JavaScriptReplyProxy reply){
        try{
            JSONObject out=new JSONObject();
            out.put("cmd","capture-state");
            out.put("active",capturing);
            out.put("session",id);
            reply.postMessage(out.toString());
        }catch(Exception ignored){}
    }
    private void replyAck(JavaScriptReplyProxy reply,String session,int seq,boolean okay){
        try{
            JSONObject out=new JSONObject();
            out.put("cmd","capture-ack");
            out.put("session",session);
            out.put("seq",seq);
            out.put("ok",okay);
            reply.postMessage(out.toString());
        }catch(Exception ignored){}
    }
    private synchronized void closeOutputs(){
        try{if(aout!=null)aout.close();}catch(Exception ignored){}
        try{if(vout!=null)vout.close();}catch(Exception ignored){}
        aout=null;vout=null;
    }
    private void finishOnDisk(){
        synchronized(this){
            if(exporting||!closing)return;
            exporting=true;closing=false;
            closeOutputs();
        }
        status="Uniendo audio y video…";update();
        File temp=new File(dir,"final.mp4");
        try{
            if(audioFile.length()<1000||videoFile.length()<1000)
                throw new IOException("Faltan fragmentos de audio o video. Inicia la captura antes de pulsar Play.");
            muxTrackFiles(videoFile,audioFile,temp);
            if(!temp.exists()||temp.length()<2000)
                throw new IOException("El archivo MP4 resultante está vacío");
            Uri created=publish(temp,"VideoDetect_"+System.currentTimeMillis()+".mp4","video/mp4");
            if(created==null)throw new IOException("No se pudo publicar en Descargas");
            savedUri=created;
            lastResult="MP4 creado en Descargas/VideoDetect · "+String.format(java.util.Locale.ROOT,"%.1f MB",temp.length()/1048576.0);
            status=lastResult;
        }catch(Exception e){
            lastResult="No se pudo combinar: "+e.getMessage();
            status=lastResult+" · Se conservaron las pistas temporales para diagnóstico.";
        }finally{
            exporting=false;update();
        }
    }
    private static int findTrack(MediaExtractor ex,String prefix)throws IOException{
        for(int i=0;i<ex.getTrackCount();i++){
            MediaFormat fmt=ex.getTrackFormat(i);
            String mime=fmt.containsKey(MediaFormat.KEY_MIME)?fmt.getString(MediaFormat.KEY_MIME):"";
            if(mime!=null&&mime.startsWith(prefix))return i;
        }
        throw new IOException("No se encontró pista "+prefix+" en los segmentos");
    }
    public static void muxTrackFiles(File video,File audio,File output)throws Exception{
        MediaExtractor ve=new MediaExtractor(),ae=new MediaExtractor();
        MediaMuxer muxer=null;
        boolean started=false;
        try{
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vi=findTrack(ve,"video/");
            int ai=findTrack(ae,"audio/");
            MediaFormat vf=ve.getTrackFormat(vi),af=ae.getTrackFormat(ai);
            ve.selectTrack(vi);ae.selectTrack(ai);
            muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vm=muxer.addTrack(vf);
            int am=muxer.addTrack(af);
            muxer.start();started=true;
            ByteBuffer bytes=ByteBuffer.allocateDirect(8*1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long lastV=-1,lastA=-1;
            while(true){
                long vt=ve.getSampleTime(),at=ae.getSampleTime();
                if(vt<0&&at<0)break;
                boolean selectVideo=vt>=0&&(at<0||vt<=at);
                MediaExtractor ex=selectVideo?ve:ae;
                int muxIndex=selectVideo?vm:am;
                long sampleTime=selectVideo?vt:at;
                long last=selectVideo?lastV:lastA;
                long sampleSize=ex.getSampleSize();
                if(sampleSize>bytes.capacity()||sampleSize<0)
                    throw new IOException("Fragmento demasiado grande o no legible");
                bytes.clear();
                int size=ex.readSampleData(bytes,0);
                if(size<0){ex.advance();continue;}
                // Ensure monotonic timestamps for each track.
                long pts=Math.max(sampleTime,last+1);
                info.set(0,size,pts,ex.getSampleFlags());
                muxer.writeSampleData(muxIndex,bytes,info);
                if(selectVideo)lastV=pts;else lastA=pts;
                ex.advance();
            }
            if(lastV<0||lastA<0)throw new IOException("No se pudieron leer muestras de ambas pistas");
            muxer.stop();started=false;
        }finally{
            try{ve.release();}catch(Exception ignored){}
            try{ae.release();}catch(Exception ignored){}
            if(muxer!=null){
                try{if(started)muxer.stop();}catch(Exception ignored){}
                try{muxer.release();}catch(Exception ignored){}
            }
        }
    }
    private Uri publish(File file,String name,String mime)throws IOException{
        if(Build.VERSION.SDK_INT<29)
            throw new IOException("Exportación pública requiere Android 10 o superior; pistas en "+dir.getAbsolutePath());
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/VideoDetect");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)return null;
        try{
            try(InputStream in=new FileInputStream(file);
                OutputStream out=context.getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IOException("No se pudo abrir destino");
                byte[] block=new byte[65536];int n;
                while((n=in.read(block))!=-1)out.write(block,0,n);
            }
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);
            context.getContentResolver().update(uri,values,null,null);
            return uri;
        }catch(Exception e){
            context.getContentResolver().delete(uri,null,null);
            throw new IOException(e);
        }
    }
    public void shutdown(){
        // Do not delete temporary media on app shutdown, allowing recovery.
        disk.shutdown();
    }
}
