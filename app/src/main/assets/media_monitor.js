(function(){
  if(window.__vdMonitorInstalled)return;
  window.__vdMonitorInstalled=true;
  var seen={};var sent=0;
  // Capture only after explicit opt-in from the native app. A tiny
  // reply-acknowledged message bridge provides backpressure so full buffer
  // data is never accumulated in a giant JS -> Android string.

  // Keep the most recent fMP4 initialization boxes even before recording.
  // This lets the user choose quality in the player BEFORE starting capture.
  var capInitAudio=null,capInitVideo=null,capInitVideosByHeight={},capInitVideoHeight=0,
      capRecordingHeight=0,capBlocked=false;
  var capInitSent={audio:false,video:false},capWaitReported=false;
  var capZeroReady=false,capZeroSeekPending=false,capZeroAttempt=0,
      capZeroRetryAt=0,capZeroLastStatus="";
  function mseInit(bytes){
    try{
      var a=bytes instanceof ArrayBuffer?new Uint8Array(bytes):
        ArrayBuffer.isView(bytes)?new Uint8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength):null;
      if(!a||a.length<16||a.length>512000)return null;
      var view=new DataView(a.buffer,a.byteOffset,a.byteLength),found=false,height=0;
      function walk(start,end,depth){
        if(depth>6)return;
        for(var off=start;off+8<=end;){
          var size=view.getUint32(off),head=8;
          var type=String.fromCharCode(a[off+4],a[off+5],a[off+6],a[off+7]);
          if(size===1){if(off+16>end)return;size=Number(view.getBigUint64(off+8));head=16;}
          else if(size===0)size=end-off;
          if(!Number.isFinite(size)||size<head||off+size>end)break;
          if(type==="moov")found=true;
          if(type==="tkhd"&&size>=96){
            var version=a[off+head],index=off+(version===1?100:88);
            if(index+4<=off+size){
              var val=view.getUint32(index)>>>16;
              if(val>=144&&val<=8640)height=val;
            }
          }
          if(type==="moov"||type==="trak")walk(off+head,off+size,depth+1);
          off+=size;
        }
      }
      walk(0,a.length,0);
      return found?{bytes:a.slice(),height:height}:null;
    }catch(e){return null;}
  }
  function rememberInit(track,bytes){
    var ini=mseInit(bytes);
    if(!ini)return false;
    if(track==="audio")capInitAudio=ini.bytes;
    else{
      if(capEnabled&&capInitSent.video&&capRecordingHeight&&ini.height&&
         ini.height!==capRecordingHeight&&!capBlocked){
        capBlocked=true;capEnabled=false;
        post("capture-integrity",location.href,
            "La pista de video cambió de "+capRecordingHeight+"p a "+ini.height+"p");
      }
      capInitVideoHeight=ini.height;
      capInitVideo=ini.bytes;
      if(ini.height)capInitVideosByHeight[String(ini.height)]=ini.bytes;
      post("capture-init",location.href,"video init "+ini.height+"p");
    }
    return true;
  }
  function ensureInitialization(){
    if(!capEnabled||!capSession||!capZeroReady)return;
    if(!capInitSent.video){
      var videoInit=capPreferredQuality==="auto"?capInitVideo:
        capInitVideosByHeight[String(capPreferredQuality)]||null;
      if(videoInit){
        var node=document.querySelector("video");
        if(node&&isFinite(node.currentTime)&&node.currentTime>0.55){
          requestRewind(node,"calidad "+(capPreferredQuality==="auto"?"actual":capPreferredQuality+"p"));
          return;
        }
        capInitSent.video=true;
        capRecordingHeight=capPreferredQuality==="auto"?capInitVideoHeight:parseInt(capPreferredQuality,10);
        captureBuffer("video",videoInit);
        post("capture-ready",location.href,"video inicial "+String(capPreferredQuality));
      }else if(!capWaitReported&&capPreferredQuality!=="auto"){
        capWaitReported=true;
        post("capture-wait",location.href,
          "esperando cabecera de "+capPreferredQuality+"p; las calidades visibles no bastan");
      }
    }
    if(capInitSent.video&&!capInitSent.audio&&capInitAudio){
      capInitSent.audio=true;
      captureBuffer("audio",capInitAudio);
    }
  }
  var capSession="",capEnabled=false,capQueue=[],capQueuedBytes=0,
      capCurrent=null,capPending=0,capSeq=0,capSending=false,
      capBufferLimit=24*1024*1024;

  function requestRewind(video,reason){
    if(!video||capZeroSeekPending||capBlocked)return;
    capZeroReady=false;capZeroSeekPending=true;capZeroAttempt++;
    capZeroRetryAt=Date.now()+1500;
    try{
      var current=Number(video.currentTime||0);
      if(!isFinite(current))current=0;
      try{video.pause();}catch(e){}
      var completed=false;
      function done(){
        if(completed)return;completed=true;capZeroSeekPending=false;
        try{video.removeEventListener("seeked",done);}catch(e){}
        var pos=Number(video.currentTime||0);
        capZeroReady=isFinite(pos)&&pos<=0.6;
        if(capZeroReady){
          if(capZeroLastStatus!=="confirmed"){
            capZeroLastStatus="confirmed";
            post("capture-zero",location.href,"inicio "+pos.toFixed(2)+"s "+reason);
          }
          ensureInitialization();
          try{video.play().catch(function(){});}catch(e){}
        }else if(capZeroAttempt>=3){
          post("capture-start-failed",location.href,"no regresó a 0:00; posición "+pos.toFixed(1)+"s");
        }
      }
      video.addEventListener("seeked",done,{once:true});
      video.currentTime=0;
      if(Number(video.currentTime||0)<=0.3)setTimeout(done,350);
      else setTimeout(done,1500);
      post("capture-seek",location.href,"retrocediendo desde "+current.toFixed(1)+"s");
    }catch(e){
      capZeroSeekPending=false;
      post("capture-start-failed",location.href,"el reproductor rechazó el salto a 0:00");
    }
  }
  function prepareZeroStart(){
    if(!capEnabled||!capSession||capBlocked)return false;
    var host="";
    try{host=location.hostname.toLowerCase();}catch(e){}
    if(host.indexOf("xtremestream.xyz")<0)return false;
    try{
      if(sessionStorage.getItem("videodetect-last-restart")!==capSession){
        sessionStorage.setItem("videodetect-last-restart",capSession);
        post("capture-restart",location.href,"reiniciando reproductor incrustado");
        location.reload();
        return false;
      }
    }catch(e){
      if(capZeroLastStatus!=="no-storage"){
        capZeroLastStatus="no-storage";
        post("capture-restart",location.href,"no hay sessionStorage, se usará seek a 0:00");
      }
    }
    var video=null;
    try{video=document.querySelector("video");}catch(e){}
    if(!video)return false;
    if(capZeroReady)return true;
    if(capZeroSeekPending||Date.now()<capZeroRetryAt)return false;
    var current=Number(video.currentTime||0);
    if(!isFinite(current)||current>0.5){
      if(capZeroAttempt>=3){
        if(capZeroLastStatus!=="failed"){
          capZeroLastStatus="failed";
          post("capture-start-failed",location.href,"no se pudo confirmar 0:00");
        }
        return false;
      }
      requestRewind(video,"inicio de captura");
      return false;
    }
    capZeroReady=true;
    if(capZeroLastStatus!=="confirmed"){
      capZeroLastStatus="confirmed";
      post("capture-zero",location.href,"posición "+current.toFixed(2)+"s");
    }
    return true;
  }
  function capturePoll(){
    try{
      if(window.VideoDetectMonitor&&VideoDetectMonitor.postMessage)
        VideoDetectMonitor.postMessage(JSON.stringify({kind:"capture-poll"}));
    }catch(e){}
  }
  function captureReset(session){
    capSession=session;capQueue=[];capQueuedBytes=0;
    capCurrent=null;capPending=0;capSeq=0;capSending=false;
    capInitSent={audio:false,video:false};capWaitReported=false;
    capRecordingHeight=0;capBlocked=false;
    capZeroReady=false;capZeroSeekPending=false;capZeroAttempt=0;
    capZeroRetryAt=0;capZeroLastStatus="";
  }
  function capturePump(){
    if(capSending||!capSession||!capQueue.length)return;
    var item=capQueue[0];
    if(item.session!==capSession){capQueuedBytes-=item.data.length;capQueue.shift();setTimeout(capturePump,0);return;}
    if(item.position>=item.data.length){
      capQueuedBytes-=item.data.length;capQueue.shift();setTimeout(capturePump,0);return;
    }
    capCurrent=item;capSending=true;capSeq++;
    capPending=capSeq;
    try{
      var data=item.data.subarray(item.position,Math.min(item.position+12000,item.data.length));
      var bin="";
      for(var i=0;i<data.length;i+=4096)
        bin+=String.fromCharCode.apply(null,data.subarray(i,Math.min(i+4096,data.length)));
      VideoDetectMonitor.postMessage(JSON.stringify({
        kind:"capture-chunk",session:capSession,seq:capPending,track:item.track,base64:btoa(bin)
      }));
    }catch(e){
      capEnabled=false;capBlocked=true;
      post("capture-integrity",location.href,"Error en envío binario del fragmento");
      capSending=false;capQueue=[];capQueuedBytes=0;
    }
  }
  function captureBuffer(track,bytes){
    if(!capEnabled||!capSession||!bytes)return;
    try{
      var view;
      if(bytes instanceof ArrayBuffer)view=new Uint8Array(bytes);
      else if(ArrayBuffer.isView(bytes))view=new Uint8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength);
      else return;
      if(!view.byteLength||view.byteLength>8*1024*1024)return;
      if(capQueuedBytes+view.byteLength>capBufferLimit){
        capEnabled=false;capBlocked=true;
        post("capture-integrity",location.href,"Cola de datos llena; faltan fragmentos");
        return;
      }
      var copy=view.slice();
      capQueue.push({session:capSession,data:copy,track:track,position:0});
      capQueuedBytes+=copy.length;
      capturePump();
    }catch(e){capEnabled=false;}
  }

  // Quality selection is best-effort: only click normal player quality controls.
  // This does not force an unavailable rendition or access protected streams.
  var capPreferredQuality="auto",qualityWasClicked="",qualityAttempts=0;
  function qualityOptions(){
    var opts=[];
    try{
      document.querySelectorAll("select option, [role=option], .vjs-menu-item, .jw-settings-content-item, .plyr__menu__container button, [class*=quality] button, [class*=quality] li, [class*=resolution] button, [data-quality], button, li")
        .forEach(function(n){
          if(opts.length>100)return;
          var text=(n.textContent||"").trim().toLowerCase().replace(/\s+/g," ");
          if(!/^(?:240|360|480|720|1080|1440|2160)p?(?:\s*(?:hd|fhd|4k))?$/.test(text))return;
          opts.push({node:n,height:parseInt(text,10)});
        });
    }catch(e){}
    return opts;
  }
  function selectPreferredQuality(){
    if(!capEnabled||!capSession||qualityWasClicked)return;
    var host="";
    try{host=location.hostname.toLowerCase();}catch(e){}
    if(host.indexOf("xtremestream.xyz")<0)return;
    if(qualityAttempts++>25)return;
    try{
      var opts=qualityOptions();
      if(!opts.length)return;
      var desired=capPreferredQuality==="auto"?
        Math.max.apply(null,opts.map(function(o){return o.height;})):
        parseInt(capPreferredQuality,10);
      var match=opts.find(function(o){return o.height===desired;});
      if(!match)return;
      var n=match.node;
      if(n.tagName==="OPTION"){
        var select=n.parentElement;
        if(select&&select.tagName==="SELECT"){
          select.value=n.value;
          select.dispatchEvent(new Event("input",{bubbles:true}));
          select.dispatchEvent(new Event("change",{bubbles:true}));
        }
      }else if(n.offsetWidth>0&&n.offsetHeight>0){
        n.click();
      }else return;
      qualityWasClicked=String(desired);
      post("quality-attempt",location.href,"solicitado "+desired+"p");
    }catch(e){}
  }
  setInterval(selectPreferredQuality,1100);
  if(window.VideoDetectMonitor){
    VideoDetectMonitor.onmessage=function(event){
      try{
        var m=JSON.parse(event.data);
        if(m.cmd==="capture-state"){
          if(m.session!==capSession){
            captureReset(m.session);
            post("capture-sync",location.href,"sesion detectada");
          }
          if(Boolean(m.active)!==capEnabled)
            post("capture-state",location.href,Boolean(m.active)?"activa":"inactiva");
          capEnabled=Boolean(m.active)&&!capBlocked;
          var preference=String(m.quality||"auto");
          if(preference!==capPreferredQuality){
            capPreferredQuality=preference;
            qualityWasClicked="";
            qualityAttempts=0;
          }
          if(capEnabled){
            selectPreferredQuality();
            if(prepareZeroStart())ensureInitialization();
          }
        }else if(m.cmd==="capture-ack"&&capCurrent&&m.seq===capPending&&m.session===capSession){
          if(m.ok){
            capCurrent.position=Math.min(capCurrent.position+12000,capCurrent.data.length);
            capSending=false;capCurrent=null;capPending=0;
            setTimeout(capturePump,0);
          }else{
            capEnabled=false;capBlocked=true;
            post("capture-integrity",location.href,"Fragmento rechazado por Android");
            capQueue=[];capQueuedBytes=0;
            capCurrent=null;capSending=false;capPending=0;
          }
        }
      }catch(e){}
    };
    capturePoll();
    setInterval(capturePoll,1000);
  }

  function post(kind,url,extra){
    if(sent>240||!window.VideoDetectMonitor||!VideoDetectMonitor.postMessage)return;
    try{
      url=typeof url==="string"?url:"";
      if(url.length>2000)url=url.slice(0,2000);
      var key=kind+"|"+url+"|"+String(extra||"");
      if(seen[key])return;seen[key]=1;sent++;
      VideoDetectMonitor.postMessage(JSON.stringify({kind:kind,url:url,detail:String(extra||"").slice(0,100),frame:location.origin}));
    }catch(e){}
  }
  try{post("frame",location.href,"marco iniciado");}catch(e){}
  function observeVideo(n){
    if(!n||n.nodeType!==1)return;
    try{
      var tag=n.tagName.toLowerCase();
      if(tag==="video"||tag==="audio"||tag==="source"){
        var u=n.currentSrc||n.src||n.getAttribute("src")||n.getAttribute("data-src");
        if(u)post("source",u,tag);
      }
      if(tag==="iframe"&&n.src)post("iframe",n.src,"iframe");
      if(n.querySelectorAll)n.querySelectorAll("video,audio,source,iframe").forEach(observeVideo);
    }catch(e){}
  }
  function scan(){
    try{document.querySelectorAll("video,audio,source,iframe").forEach(observeVideo);}catch(e){}
    try{
      performance.getEntriesByType("resource").forEach(function(item){
        if(/\.(mp4|webm|m3u8|mpd|m4v)(?:[/?#]|$)|\/get_file\//i.test(item.name))post("resource",item.name,"performance");
      });
    }catch(e){}
  }

  // Inspect only DOM elements in the embedded video player. We cannot
  // assert a rendition exists until its menu or CDN request exposes it.
  function inspectOfferedQualities(){
    try{
      var host=location.hostname.toLowerCase();
      if(host.indexOf("xtremestream.xyz")<0)return;
      document.querySelectorAll("option,[role=option],[data-quality],.vjs-menu-item,.jw-settings-content-item,[class*=quality] li,[class*=quality] button")
        .forEach(function(n){
          var hint=[n.getAttribute("data-quality"),n.getAttribute("data-label"),
                    n.getAttribute("value"),n.textContent||""].filter(Boolean).join(" ");
          var m=hint.match(/(?:^|[^0-9])(2160|1440|1080|720|480|360|240)\s*p?(?=[^0-9]|$)/i);
          if(m)post("quality-offered",location.href,m[1]+"p");
        });
    }catch(e){}
  }

  function inspectQualityConfiguration(){
    try{
      var host=(location.hostname||"").toLowerCase();
      if(host.indexOf("xtremestream.xyz")<0)return;
      qualityOptions().forEach(function(item){
        post("quality-offered",location.href,item.height+"p");
      });
      document.querySelectorAll("script:not([src])").forEach(function(script){
        var body=script.textContent||"";
        if(body.length>1000000)return;
        var matches=body.matchAll(/(?:["']?quality["']?|["']?resolution["']?|["']?label["']?|["']?sources["']?|["']?levels["']?)[^\n\r]{0,120}?(2160|1440|1080|720|480|360|240)\s*p?\b|(?:2160|1440|1080|720|480|360|240)_enc/gi);
        var count=0;
        for(var match of matches){
          if(++count>18)break;
          var resolution=match[1]||((match[0]||"").match(/(2160|1440|1080|720|480|360|240)_enc/)||[])[1];
          if(resolution)post("quality-hint",location.href,resolution+"p");
        }
      });
      if(typeof window.jwplayer==="function"){
        try{
          var instance=window.jwplayer();
          var levels=instance&&instance.getQualityLevels&&instance.getQualityLevels();
          if(Array.isArray(levels))levels.forEach(function(level){
            var match=String(level.label||"").match(/(2160|1440|1080|720|480|360|240)/);
            if(match)post("quality-offered",location.href,match[1]+"p");
          });
        }catch(e){}
      }
    }catch(e){}
  }
  [1500,3000,5000,8000,11000].forEach(function(ms){
    setTimeout(inspectQualityConfiguration,ms);
  });
  setInterval(inspectOfferedQualities,1800);
  function start(){
    try{
      scan();
      inspectOfferedQualities();
      inspectQualityConfiguration();
      new MutationObserver(function(items){
        items.forEach(function(item){
          if(item.type==="attributes")observeVideo(item.target);
          else item.addedNodes.forEach(observeVideo);
        });
      }).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:["src","data-src"]});
      document.addEventListener("play",function(e){
        var t=e.target;if(t&&t.tagName){
          post("play",t.currentSrc||t.src||location.href,t.tagName);
          setTimeout(scan,700);
        }
      },true);
      document.addEventListener("loadedmetadata",function(e){
        var t=e.target;
        if(t&&t.tagName)post("metadata",t.currentSrc||t.src||location.href,
           String(t.videoWidth||0)+"x"+String(t.videoHeight||0));
      },true);
      
      // These events expose codec and resolution metadata only, never raw video data.
      document.addEventListener("resize",function(e){
        var t=e.target;
        if(t&&t.tagName==="VIDEO")post("resize",t.currentSrc||t.src||location.href,
          String(t.videoWidth||0)+"x"+String(t.videoHeight||0));
      },true);
      document.addEventListener("encrypted",function(e){
        var t=e.target;
        post("encrypted",t&&(t.currentSrc||t.src)||location.href,
          String(e.initDataType||"unknown"));
      },true);
      document.addEventListener("error",function(e){
        var t=e.target;if(t&&t.tagName&&/^(VIDEO|AUDIO|SOURCE)$/i.test(t.tagName))
          post("media-error",t.currentSrc||t.src||location.href,t.tagName);
      },true);
      if(window.PerformanceObserver){
        var o=new PerformanceObserver(function(list){
          list.getEntries().forEach(function(x){
            if(/\.(mp4|webm|m3u8|mpd|m4v)(?:[/?#]|$)|\/get_file\//i.test(x.name))
               post("resource",x.name,"timing");
          });
        });
        try{o.observe({entryTypes:["resource"]});}catch(e){}
      }
    }catch(e){post("js-error",location.href,String(e));}
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",start,{once:true});
  else start();
  try{

    // Record whether the player feeds media through MSE and which MIME codecs it requests.
    // Keep native return values and arguments unchanged. Never read segment bytes or keys.
    var MSE=window.MediaSource;
    if(MSE&&MSE.prototype&&typeof MSE.prototype.addSourceBuffer==="function"){
      var bufferTypes=new WeakMap(),bufferCounts=new WeakMap();
      var originalBuffer=MSE.prototype.addSourceBuffer;
      MSE.prototype.addSourceBuffer=function(mime){
        var buffer=originalBuffer.apply(this,arguments);
        try{bufferTypes.set(buffer,String(mime));post("mse",location.href,String(mime));}catch(e){}
        return buffer;
      };
      if(window.SourceBuffer&&SourceBuffer.prototype&&typeof SourceBuffer.prototype.appendBuffer==="function"){
        var originalAppend=SourceBuffer.prototype.appendBuffer;
        SourceBuffer.prototype.appendBuffer=function(bytes){
          var trackType=String(bufferTypes.get(this)||"");
          var captured=null;
          if(capEnabled&&(trackType.indexOf("audio/mp4")===0||trackType.indexOf("video/mp4")===0)){
            try{
              if(bytes instanceof ArrayBuffer)captured=new Uint8Array(bytes).slice();
              else if(ArrayBuffer.isView(bytes))
                captured=new Uint8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength).slice();
            }catch(e){}
          }
          var track=trackType.indexOf("audio/")===0?"audio":"video";
          var isInit=rememberInit(track,bytes);
          var originalResult=originalAppend.apply(this,arguments);
          if(capEnabled){
            if(prepareZeroStart())ensureInitialization();
            if(!isInit&&capZeroReady&&capInitSent.video&&capInitSent[track]){
              if(!captured){
                try{
                  if(bytes instanceof ArrayBuffer)captured=new Uint8Array(bytes).slice();
                  else if(ArrayBuffer.isView(bytes))
                    captured=new Uint8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength).slice();
                }catch(e){}
              }
              if(captured)captureBuffer(track,captured);
            }
            if(captured)post("capture-queued",location.href,
              track+" bytes="+captured.length+" init="+isInit);
          }
          try{
            var count=(bufferCounts.get(this)||0)+1;
            bufferCounts.set(this,count);
            if(count<=3||count===10||count%25===0){
              var size=bytes&&typeof bytes.byteLength==="number"?bytes.byteLength:-1;
              post("mse-append",location.href,
                "buffer="+String(bufferTypes.get(this)||"unknown")+
                " count="+count+" bytes="+size);
            }
          }catch(e){}
          return originalResult;
        };
      }
    }
    var originalFetch=window.fetch;
    if(typeof originalFetch==="function")window.fetch=function(input){
      var requestUrl="";
      try{requestUrl=typeof input==="string"?input:input&&input.url||"";post("fetch",requestUrl,"fetch");}catch(e){}
      var promise=originalFetch.apply(this,arguments);
      try{
        if(promise&&typeof promise.then==="function"&&requestUrl.indexOf("/cdn/down/")!==-1){
          promise.then(function(response){
            try{
              var type=response.headers.get("content-type")||"(sin tipo)";
              var size=response.headers.get("content-length")||"?";
              post("fetch-response",requestUrl,
                "HTTP "+response.status+" tipo="+type+" tamaño="+size);
            }catch(e){}
          },function(error){post("fetch-failed",requestUrl,"solicitud rechazada");});
        }
      }catch(e){}
      return promise;
    };
    var oldOpen=XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open=function(method,url){
      try{post("xhr",url,String(method));}catch(e){}
      return oldOpen.apply(this,arguments);
    };
  }catch(e){}
})();