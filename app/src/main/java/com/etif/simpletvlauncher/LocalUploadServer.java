package com.etif.simpletvlauncher;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.*;
import org.json.JSONObject;
import android.content.SharedPreferences;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Temporary LAN-only upload endpoint. No external libraries. */
public final class LocalUploadServer {
    private static final long MAX_UPLOAD = 512L * 1024 * 1024;
    private final Context context;
    private ServerSocket socket;
    private ExecutorService pool;
    private volatile boolean running;
    private int port;
    private final Set<String> reservedNames = new HashSet<>();
    private static final String DOWNLOAD_SUBDIR = Environment.DIRECTORY_DOWNLOADS + "/";
    LocalUploadServer(Context context) {
        this.context=context.getApplicationContext();
    }

    String address() {return "http://" + ip() + ":" + port + "/";}
    void start() throws IOException {
        socket=new ServerSocket(8080,12);
        socket.setReuseAddress(true);
        port=socket.getLocalPort();
        running=true;
        pool=Executors.newFixedThreadPool(3);
        Thread accept=new Thread(()->{
            while(running) {
                try { Socket peer=socket.accept(); peer.setSoTimeout(45000);pool.execute(()->handle(peer)); }
                catch(IOException e) {if (!running) break;}
                catch(Exception ignored) {}
            }
        },"TV-Upload-Accept");
        accept.setDaemon(true);accept.start();
    }
    void close(){running=false;try{if(socket!=null)socket.close();}catch(IOException ignored){} if(pool!=null)pool.shutdownNow();}
    private static String ip(){
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()) {
                NetworkInterface iface=interfaces.nextElement();
                if(!iface.isUp()||iface.isLoopback())continue;
                Enumeration<InetAddress> addresses=iface.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress a=addresses.nextElement();
                    if(a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return "IP no disponible";
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<8192;i++){
            int c=in.read();if(c<0)break;
            if(c=='\n')break;
            if(c!='\r')out.write(c);
        }
        return out.toString("ISO-8859-1");
    }

    // Reserve a unique filename before creating the MediaStore entry.  This avoids
    // Android appending a suffix AFTER the extension (e.g. video.mp4 (1)).
    private synchronized String reserveName(String original,String folder) {
        int dot = original.lastIndexOf('.');
        String stem = dot > 0 ? original.substring(0, dot) : original;
        String ext = dot > 0 ? original.substring(dot) : "";
        for (int n = 0; n < 100000; n++) {
            String candidate = n == 0 ? original : stem + " (" + n + ")" + ext;
            if (reservedNames.contains(folder+"/"+candidate) || exists(candidate,folder)) continue;
            reservedNames.add(folder+"/"+candidate);
            return candidate;
        }
        throw new IllegalStateException("Too many files with the same name");
    }
    private synchronized void releaseName(String name,String folder) { reservedNames.remove(folder+"/"+name); }
    private boolean exists(String name,String folder) {
        if (Build.VERSION.SDK_INT >= 29) {
            String[] projection = {MediaStore.MediaColumns._ID};
            String where = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            try (Cursor c = context.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, where,
                    new String[]{name, relativeDir(folder)}, null)) {
                return c != null && c.moveToFirst();
            }
        }
        File base = legacyDir(folder);
        return base != null && new File(base, name).exists();
    }

    private void handle(Socket peer){
        try(Socket s=peer) {
            InputStream in=new BufferedInputStream(s.getInputStream());
            OutputStream out=new BufferedOutputStream(s.getOutputStream());
            String request=line(in);String[] segments=request.split(" ");
            if(segments.length!=3){reply(out,400,"Bad request");return;}
            String method=segments[0],path=segments[1];
            long len=-1;
            for(int i=0;i<48;i++){
                String header=line(in);
                if(header.isEmpty())break;
                int idx=header.indexOf(':');if(idx<0)continue;
                String key=header.substring(0,idx).trim().toLowerCase(Locale.US),v=header.substring(idx+1).trim();
                if(key.equals("content-length"))try{len=Long.parseLong(v);}catch(Exception ignored){}
            }
            if(method.equals("GET") && (path.equals("/")||path.startsWith("/?"))){page(out);return;}
            if(method.equals("GET") && (path.equals("/files") || path.startsWith("/files?"))){ listFiles(out,path); return; }
            if(method.equals("GET") && path.startsWith("/download?")){ downloadFile(out,path);return; }
            if(method.equals("POST") && (path.equals("/delete") || path.startsWith("/delete?"))){deleteFile(out,path);return;}
            if(!method.equals("POST") || !path.startsWith("/upload?name=")){reply(out,404,"Not found");return;}
            if(len<0 ||len>MAX_UPLOAD){reply(out,413,"File exceeds 512 MB or missing length");return;}
            String raw=queryValue(path,"name");
            String uploadPath=safeFolder(queryValue(path,"path"));
            if(!isAllowedFolder(uploadPath)){reply(out,403,"Folder is not available for upload");return;}
            String name=raw.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
            name=name.replaceAll("[\\x00-\\x1f\\x7f<>:\"|?*]","_").trim();
            if(name.isEmpty() || name.equals(".") || name.equals("..")){reply(out,400,"Invalid file name");return;}
            String uniqueName;
            try { uniqueName = reserveName(name,uploadPath); }
            catch (Exception e) { reply(out,500,"Cannot reserve filename");return; }
            Uri saved=null;File old=null;
            try {
                OutputStream target;
                if(Build.VERSION.SDK_INT>=29){
                    ContentValues values=new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME,uniqueName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,relativeDir(uploadPath));
                    values.put(MediaStore.MediaColumns.IS_PENDING,1);
                    saved=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                    if(saved==null)throw new IOException("Cannot create download");
                    target=context.getContentResolver().openOutputStream(saved);
                    if(target==null)throw new IOException("Cannot open download");
                } else {
                    // Older Android versions need storage permission for public Downloads.
                    // Keep files in app storage to avoid broad write access.
                    File dir=legacyDir(uploadPath);
                    if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create directory");
                    old=new File(dir,uniqueName);target=new FileOutputStream(old);
                }
                try(OutputStream dest=target) {
                    byte[] buf=new byte[32768];long remaining=len;
                    while(remaining>0){int n=in.read(buf,0,(int)Math.min(buf.length,remaining));if(n<0)throw new EOFException("Upload interrupted");dest.write(buf,0,n);remaining-=n;}
                    dest.flush();
                }
                if(saved!=null){ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(saved,values,null,null);}
                reply(out,200,"Uploaded: "+uniqueName);
            }catch(Exception ex){
                if(saved!=null)context.getContentResolver().delete(saved,null,null);
                if(old!=null)old.delete();
                reply(out,500,"Upload failed: "+ex.getMessage());
            } finally {
                releaseName(uniqueName,uploadPath);
            }
        }catch(Exception ignored){}
    }
    private static void reply(OutputStream out,int status,String message)throws IOException{
        byte[] bytes=message.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 "+status+" Response\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);out.flush();
    }
    private static final class DownloadItem {
        String id, name, folder; long size, modified; boolean directory; Uri uri; File file;
    }
    // Files and inferred subdirectories within public Downloads. Android storage
    // restrictions are respected: only entries readable by this application appear.
    private static String queryValue(String request, String key) throws UnsupportedEncodingException {
        int pos=request.indexOf('?'); if(pos<0)return "";
        for(String part:request.substring(pos+1).split("&")) {
            int equal=part.indexOf('=');
            if(equal>0 && part.substring(0,equal).equals(key))
                return URLDecoder.decode(part.substring(equal+1),"UTF-8");
        }
        return "";
    }
    private static String safeFolder(String input) {
        if(input==null || input.isEmpty())return "";
        input=input.replace('\\','/');
        StringBuilder result=new StringBuilder();
        for(String seg:input.split("/")) {
            if(seg.isEmpty() || seg.equals("."))continue;
            if(seg.equals("..") || seg.indexOf(':')>=0 || seg.indexOf((char)0)>=0)
                return "";
            if(result.length()>0)result.append('/');
            result.append(seg);
        }
        return result.toString();
    }
    private static String relativeDir(String folder){return (folder.isEmpty()?"":folder+"/");}
    private static boolean isAllowedFolder(String folder){
        if(folder.isEmpty())return false;
        for(String seg:folder.split("/"))if(seg.equals(".")||seg.equals("..")||seg.isEmpty())return false;
        return true;
    }
    private File legacyDir(String folder){
        File base=context.getExternalFilesDir(null);
        if(base==null)return new File(context.getFilesDir(),"downloads");
        // Android 9 and earlier: app-private files only when public storage access not granted.
        return new File(base,folder);
    }
    private java.util.List<DownloadItem> downloads(String folder) {
        java.util.TreeMap<String,DownloadItem> entries=new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if(folder.isEmpty()) {
            String[] roots={"Download","Documents","Pictures","Movies","Music","DCIM","Podcasts","Ringtones","Audiobooks"};
            for(String root:roots){DownloadItem d=new DownloadItem();d.name=root;d.directory=true;entries.put("D:"+root,d);}
        }
        String prefix=relativeDir(folder);
        if(Build.VERSION.SDK_INT>=29) {
            Uri[] collections={MediaStore.Downloads.EXTERNAL_CONTENT_URI,MediaStore.Files.getContentUri("external")};
            String[] cols={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DATE_MODIFIED};
            for(Uri collection:collections)try(Cursor c=context.getContentResolver().query(collection,cols,null,null,null)) {
                if(c!=null)while(c.moveToNext()){
                    String relative=c.getString(3),name=c.getString(1);
                    if(relative==null||name==null||!relative.startsWith(prefix))continue;
                    String remainder=relative.substring(prefix.length());
                    if(!remainder.isEmpty()){
                        String next=remainder.split("/")[0];
                        if(!next.isEmpty()) {DownloadItem dir=new DownloadItem();dir.name=next;dir.directory=true;entries.put("D:"+next,dir);}
                    } else if(!folder.isEmpty()){
                        DownloadItem item=new DownloadItem();item.id=String.valueOf(c.getLong(0));
                        item.name=name;item.size=c.getLong(2);item.modified=c.isNull(4)?0:c.getLong(4)*1000;
                        item.uri=android.content.ContentUris.withAppendedId(collection,c.getLong(0));
                        entries.put("F:"+name,item);
                    }
                }
            }catch(Exception ignored){}
        }
        File root=Build.VERSION.SDK_INT>=29?new File(Environment.getExternalStorageDirectory(),folder):legacyDir(folder);
        File[] files=null;try{files=root.listFiles();}catch(Exception ignored){}
        if(files!=null)for(File f:files){
            DownloadItem item=new DownloadItem();item.name=f.getName();item.directory=f.isDirectory();
            item.id=f.getName();item.size=f.length();item.modified=f.lastModified();item.file=f;
            String key=(item.directory?"D:":"F:")+item.name;
            if(!entries.containsKey(key))entries.put(key,item);
        }
        return new java.util.ArrayList<>(entries.values());
    }
    private void deleteFile(OutputStream out,String request)throws IOException {
        String folder=safeFolder(queryValue(request,"path")),id=queryValue(request,"id");
        if(folder.isEmpty()){reply(out,403,"Cannot delete storage root");return;}
        DownloadItem target=null;
        for(DownloadItem item:downloads(folder))if(!item.directory && (item.id.equals(id) || item.name.equals(id))){target=item;break;}
        if(target==null){reply(out,404,"File not found");return;}
        try {
            int count=target.uri!=null?context.getContentResolver().delete(target.uri,null,null)
                :(target.file!=null&&target.file.delete()?1:0);
            if(count>0)reply(out,200,"File deleted");else reply(out,403,"Android does not allow deleting this file");
        }catch(Exception e){reply(out,403,"Android requires permission to delete this file");}
    }
    private static String jsonEscape(String s) {
        return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    private void listFiles(OutputStream out,String request)throws IOException {
        String folder=safeFolder(queryValue(request,"path"));
        StringBuilder data=new StringBuilder("{\"path\":"+jsonEscape(folder)+",\"entries\":[");
        for(DownloadItem file:downloads(folder)) {
            if(data.charAt(data.length()-1)!='[')data.append(',');
            data.append("{\"id\":").append(jsonEscape(file.id==null?"":file.id))
                .append(",\"name\":").append(jsonEscape(file.name))
                .append(",\"directory\":").append(file.directory)
                .append(",\"modified\":").append(file.modified)
                .append(",\"size\":").append(file.size).append('}');
        }
        data.append("]}");byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }
    private void downloadFile(OutputStream out,String request)throws IOException {
        String id=queryValue(request,"id"),folder=safeFolder(queryValue(request,"path"));
        DownloadItem target=null;
        for(DownloadItem entry:downloads(folder))if(!entry.directory && entry.id.equals(id)){target=entry;break;}
        if(target==null){reply(out,404,"File not found");return;}
        InputStream in;
        try{in=target.uri!=null?context.getContentResolver().openInputStream(target.uri):new FileInputStream(target.file);}
        catch(Exception e){reply(out,403,"File access unavailable");return;}
        if(in==null){reply(out,404,"File unavailable");return;}
        String safeName=target.name.replaceAll("[\r\n\"]","_");
        try(InputStream src=in){
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\""+safeName+"\"\r\nContent-Length: "+target.size+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] buffer=new byte[65536];int n;while((n=src.read(buffer))!=-1)out.write(buffer,0,n);out.flush();
        }
    }
    private String webTranslations() {
        String code=context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).getString("language", "auto");
        if ("auto".equals(code)) code=Locale.getDefault().getLanguage();
        if (!java.util.Arrays.asList("es","en","ca","ja","zh","fr","de","pt","it","ru").contains(code)) code="es";
        try(InputStream in=context.getAssets().open("lang_"+code+".json")) {
            ByteArrayOutputStream buf=new ByteArrayOutputStream();
            byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1)buf.write(b,0,n);
            JSONObject obj=new JSONObject(buf.toString("UTF-8"));
            String uploadText="Subir";
            switch(code) {
                case "en": uploadText="Upload";break;
                case "ca": uploadText="Pujar";break;
                case "fr": uploadText="Envoyer";break;
                case "de": uploadText="Hochladen";break;
                case "pt": uploadText="Enviar";break;
                case "it": uploadText="Carica";break;
                case "ru": uploadText="Загрузить";break;
                case "ja": uploadText="アップロード";break;
                case "zh": uploadText="上传";break;
            }
            obj.put("Subir",uploadText);
            // Escape raw HTML-significant characters to keep the embedded JSON safe.
            return obj.toString().replace("<", "\\u003c");
        }catch(Exception ex){return "{}";}
    }
    private void page(OutputStream out)throws IOException{
        String html=
           "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Archivos · Simple TV</title><style>"
          +":root{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;color:#191d24;background:#f3f4f6}*{box-sizing:border-box}body{margin:0}.shell{max-width:1500px;margin:auto;padding:26px 28px 55px}header{display:flex;align-items:center;justify-content:space-between;gap:15px;margin-bottom:26px}.brand{font-weight:800;font-size:24px;letter-spacing:-.8px}.sub{color:#78766f;font-size:13px}.dot{display:inline-block;background:#5d7957;width:9px;height:9px;border-radius:50%;margin-right:7px}.grid{display:grid;grid-template-columns:minmax(0,1fr);gap:22px}.panel{background:white;border:1px solid #e3e5e9;border-radius:12px;overflow:hidden;box-shadow:0 8px 26px #211e1610}.pad{padding:21px}.title{font-size:18px;font-weight:700;margin:0}.toolbar{display:flex;align-items:center;justify-content:flex-start;gap:7px}.toolbar .title{margin-right:auto}.crumb{margin:17px 0 15px;display:flex;flex-wrap:wrap;align-items:center;gap:6px;font-size:14px}.crumb button{border:0;padding:5px 8px;background:#efeee8;color:#514d40;border-radius:7px;cursor:pointer}.crumb button:hover{background:#e1dfd5}.actions{display:flex;gap:7px;align-items:center}button{cursor:pointer;font:inherit}button.secondary{border:1px solid #dedcd2;border-radius:9px;background:white;color:#333; padding:8px 12px}.filehead,.entry{display:grid;grid-template-columns:minmax(0,1fr) 90px 157px 100px;align-items:center;gap:10px;padding:12px 19px}.filehead{border-top:1px solid #efede7;border-bottom:1px solid #efede7;color:#99978d;font-size:12px}.entry{min-height:57px;border-bottom:1px solid #f1f0eb;font-size:13px}.entry:last-child{border-bottom:0}.entry:hover{background:#f3f6fa}.fname{display:flex;align-items:center;gap:12px;min-width:0}.fname span:last-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.glyph{width:34px;height:34px;display:grid;place-items:center;border-radius:9px;background:#f0eee8;font-size:19px;flex:none}.folder .glyph{background:#faf0d7}.folder .fname{cursor:pointer;font-weight:650}.dim{color:#8c897e}.tiny{font-size:12px}.dl{border:0;color:#536849;background:#ecf0e8;border-radius:8px;padding:7px 8px;text-decoration:none;text-align:center;font-size:12px}.dl:hover{background:#dae6d4}#drop{margin-top:20px;border:2px dashed #c5c3b7;background:#faf9f5;border-radius:13px;padding:46px 15px;text-align:center;cursor:pointer;transition:.13s}#drop.over{background:#e9efdf;border-color:#688461}#drop .dropicon{display:inline-flex;align-items:center;justify-content:center;width:54px;height:54px;color:#54584c}#drop .dropicon svg{width:42px;height:42px;stroke:currentColor;stroke-width:1.7;fill:none;stroke-linecap:round;stroke-linejoin:round}#drop b{display:block;margin:9px 0}#choose{margin-top:14px;background:#393b30;color:white;border:0;border-radius:9px;padding:12px 16px;width:100%}#choose:disabled{opacity:.5}#result,#chosen{white-space:pre-wrap;overflow-wrap:anywhere;color:#666357;font-size:13px;margin:13px 0 0}.warning{font-size:12px;color:#777268;line-height:1.6}.hidden{display:none!important}.iconbtn{display:inline-flex;align-items:center;justify-content:center;min-width:35px;height:35px;border:1px solid #dedcd2;background:#fff;border-radius:9px;color:#555} .iconbtn svg{width:19px;height:19px;stroke:currentColor;stroke-width:1.8;fill:none;stroke-linecap:round;stroke-linejoin:round}.danger{color:#a63f3a;border-color:#edd5d2} .uploadcard{margin-top:16px}.empty{padding:35px 18px;text-align:center;color:#8c897e}@media(max-width:850px){.grid{grid-template-columns:1fr}.shell{padding:18px 13px}.filehead,.entry{grid-template-columns:minmax(0,1fr) 74px}.filehead span:nth-child(2),.filehead span:nth-child(3),.entry .size,.entry .date{display:none}.brand{font-size:20px}}"
          +".brand{font-weight:750}.panel{box-shadow:0 8px 25px #1b263311}.filehead{background:#f1f3f5;color:#4e5662}.glyph{background:#eef1f4}.folder .glyph{background:#fff4d8}.shell{padding-top:22px}.toolbar .title{font-size:20px}.iconbtn{width:40px;height:40px} </style></head><body><div class=\"shell\"><header><div><div class=\"brand\">Simple TV Launcher</div><div class=\"sub\">File Manager</div></div><div class=\"sub\"><span class=\"dot\"></span>Servidor local · 8080</div></header><div class=\"grid\"><section class=\"panel\"><div class=\"pad\"><div class=\"toolbar\"><h2 class=\"title\">File Manager</h2><button class=\"iconbtn\" id=\"upbtn\" title=\"Subir archivos\" aria-label=\"Subir archivos\"><svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M12 16V3m-5 5 5-5 5 5M4 16v4h16v-4\"/></svg></button><button class=\"iconbtn\" id=\"reload\" title=\"Actualizar\" aria-label=\"Actualizar\"><svg viewBox=\"0 0 24 24\"><path d=\"M20 11a8 8 0 1 0-2.3 6\"/><path d=\"M20 4v7h-7\"/></svg></button></div><nav class=\"crumb\" id=\"crumb\"></nav></div><div class=\"filehead\"><span>Nombre</span><span>Tamaño</span><span>Modificado</span><span></span></div><div id=\"listing\" class=\"empty\">Cargando archivos…</div></section><aside class=\"panel pad uploadcard hidden\" id=\"uploadPanel\"><h2 class=\"title\">Enviar archivos a la TV</h2><p class=\"sub\">Arrastra archivos aquí o selecciónalos desde tu dispositivo.</p><div id=\"drop\" tabindex=\"0\" role=\"button\"><span class=\"dropicon\"><svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M12 16V3m-5 5 5-5 5 5M4 16v4h16v-4\"/></svg></span><b>Arrastra y suelta</b><span class=\"sub\">o pulsa para seleccionar</span></div><input id=\"file\" type=\"file\" multiple hidden><div id=\"chosen\">Ningún archivo seleccionado</div><button id=\"choose\">Subir</button><div id=\"result\" role=\"status\"></div><p class=\"warning\">Máximo 512 MB por archivo.</p></aside></div></div><script>"
          +"let current='Download',chosen=[];const box=document.getElementById('listing'),crumb=document.getElementById('crumb'),drop=document.getElementById('drop'),inp=document.getElementById('file'),r=document.getElementById('result');"
          +"const fmt=n=>n<1024?n+' B':n<1048576?(n/1024).toFixed(1)+' KB':(n/1048576).toFixed(1)+' MB';"
          +"function browse(p){current=p;refresh()}"
          +"function crumbs(){crumb.replaceChildren();let segments=current?current.split('/'):[],all=[T('Almacenamiento'),...segments];for(let i=0;i<all.length;i++){let b=document.createElement('button');b.textContent=all[i];b.onclick=()=>browse(segments.slice(0,i).join('/'));crumb.appendChild(b);if(i<all.length-1)crumb.append(' › ')}}"
          +"function el(tag,klass,content){let x=document.createElement(tag);if(klass)x.className=klass;if(content!==undefined)x.textContent=content;return x}"
          +"async function refresh(){crumbs();box.className='';box.replaceChildren();try{let response=await fetch('/files?path='+encodeURIComponent(current));if(!response.ok)throw Error(await response.text());let data=await response.json();if(!data.entries.length){box.className='empty';box.textContent=T('Esta carpeta no contiene archivos accesibles');return}data.entries.sort((a,b)=>Number(b.directory)-Number(a.directory)||a.name.localeCompare(b.name,'es'));for(let f of data.entries){let row=el('div','entry'+(f.directory?' folder':'')),name=el('div','fname'),glyph=el('span','glyph',f.directory?'▰':'▤'),title=el('span','',f.name);name.append(glyph,title);if(f.directory){name.onclick=()=>browse(current?current+'/'+f.name:f.name);row.onclick=()=>browse(current?current+'/'+f.name:f.name);row.style.cursor='pointer'}row.append(name,el('span','size dim',f.directory?'—':fmt(f.size)),el('span','date dim',f.modified?new Date(f.modified).toLocaleString(WEB_LOCALE,{dateStyle:'short',timeStyle:'short'}):'—'));if(!f.directory){let link=el('a','iconbtn','');link.title=T('Descargar');link.setAttribute('aria-label',T('Descargar'));link.innerHTML='<svg viewBox=\"0 0 24 24\"><path d=\"M12 3v12m-4-4 4 4 4-4M4 17v4h16v-4\"/></svg>'; link.href='/download?path='+encodeURIComponent(current)+'&id='+encodeURIComponent(f.id);link.download=f.name;let del=el('button','iconbtn danger','');del.title=T('Eliminar');del.setAttribute('aria-label',T('Eliminar'));del.innerHTML='<svg viewBox=\"0 0 24 24\"><path d=\"M4 7h16M10 11v7m4-7v7M6 7l1 14h10l1-14M9 7V4h6v3\"/></svg>';del.onclick=async()=>{if(!confirm(T('¿Eliminar ')+f.name+'?'))return;let resp=await fetch('/delete?path='+encodeURIComponent(current)+'&id='+encodeURIComponent(f.id),{method:'POST'});if(!resp.ok)alert(await resp.text());await refresh()};let actions=el('div','actions');actions.append(link,del);row.append(actions)}else row.append(el('span','dim','›'));box.append(row)}}catch(e){box.className='empty';box.textContent=T('No es posible consultar esta carpeta: ')+e.message}}"
          +"document.getElementById('reload').onclick=refresh;document.getElementById('upbtn').onclick=()=>document.getElementById('uploadPanel').classList.toggle('hidden');drop.onclick=()=>inp.click();drop.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();inp.click()}};inp.onchange=()=>pick(inp.files);function pick(files){chosen=Array.from(files);document.getElementById('chosen').textContent=chosen.length?chosen.map(f=>f.name).join('\\n'):T('Ningún archivo seleccionado')}for(let ev of ['dragover','dragenter'])drop.addEventListener(ev,e=>{e.preventDefault();drop.classList.add('over')});for(let ev of ['dragleave','drop'])drop.addEventListener(ev,e=>{e.preventDefault();drop.classList.remove('over')});drop.addEventListener('drop',e=>pick(e.dataTransfer.files));document.addEventListener('dragover',e=>e.preventDefault());document.addEventListener('drop',e=>e.preventDefault());document.getElementById('choose').onclick=async()=>{if(!chosen.length){r.textContent=T('Selecciona al menos un archivo');return}let btn=document.getElementById('choose');btn.disabled=true;try{for(let j=0;j<chosen.length;j++){let f=chosen[j];r.textContent=T('Subiendo ')+(j+1)+T(' de ')+chosen.length+': '+f.name;try{let response=await fetch('/upload?name='+encodeURIComponent(f.name)+'&path='+encodeURIComponent(current),{method:'POST',body:f});let t=await response.text();if(!response.ok)throw Error(t);r.textContent+='\\n'+t}catch(e){r.textContent+='\\n'+T('Error: ')+e.message;break}}await refresh()}finally{btn.disabled=false}};refresh();</script></body></html>";
        String lang=context.getSharedPreferences("launcher_prefs",Context.MODE_PRIVATE).getString("language","auto");
        if ("auto".equals(lang)) lang=Locale.getDefault().getLanguage();
        html=html.replace("<script>","<script>const WEB_I18N="+webTranslations()+";const WEB_LOCALE="+JSONObject.quote(lang)+";function T(x){return WEB_I18N[x]||x}</script><script>");
        html=html.replace("</body></html>","<script>(function(){const walk=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);let n;while(n=walk.nextNode()){let v=n.nodeValue,t=v.trim();if(t&&T(t)!==t)n.nodeValue=v.replace(t,T(t));}document.querySelectorAll('[title],[aria-label]').forEach(e=>{for(let a of ['title','aria-label'])if(e.hasAttribute(a))e.setAttribute(a,T(e.getAttribute(a)));});document.documentElement.lang=WEB_LOCALE;document.title=T('Archivos · Simple TV');})();</script></body></html>");
        byte[] bytes=html.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }
}
