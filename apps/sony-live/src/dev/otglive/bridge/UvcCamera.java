package dev.otglive.bridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.*;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sony ZV-1 UVC MJPEG bulk reader. No native driver, root, or virtual camera. */
public final class UvcCamera implements AutoCloseable {
    public interface Listener {
        void status(String message);
        void jpeg(byte[] jpeg, long timestampUs);
    }
    private static final String TAG = "OtgLive";
    private final Context context;
    private final Listener listener;
    private final UsbManager manager;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile UsbDeviceConnection connection;
    private BroadcastReceiver receiver;
    private Thread worker;
    private long receivedFrames, streamStarted;

    public UvcCamera(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        UsbDevice sony = null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == 0x054c && device.getProductId() == 0x0de4) {
                sony = device; break;
            }
        }
        if (sony == null) { fail("Sony ZV-1 USB Streaming tidak ditemukan"); return; }
        if (manager.hasPermission(sony)) { open(sony); return; }
        final UsbDevice device = sony;
        final String action = context.getPackageName() + ".OTG_LIVE_USB_PERMISSION";
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!action.equals(intent.getAction())) return;
                try { context.unregisterReceiver(this); } catch (RuntimeException ignored) {}
                receiver = null;
                if (!running.get()) return;
                if (manager.hasPermission(device)) open(device);
                else fail("Izin USB ditolak");
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
        PendingIntent permission = PendingIntent.getBroadcast(context, 5701,
                new Intent(action).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        manager.requestPermission(device, permission);
        say("Menunggu izin USB untuk Sony ZV-1");
    }
    private void open(UsbDevice device) {
        worker = new Thread(() -> {
            try { stream(device); }
            catch (Throwable error) {
                if (running.get()) fail(error.toString());
                Log.e(TAG, "USB capture failed", error);
            } finally {
                running.set(false);
                UsbDeviceConnection c = connection; connection = null;
                if (c != null) c.close();
            }
        }, "OtgLive-UVC");
        worker.start();
    }
    private void stream(UsbDevice device) throws Exception {
        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) throw new IllegalStateException("Tidak dapat membuka ZV-1");
        connection = c;
        UsbInterface stream = null, control = null;
        UsbEndpoint endpoint = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() != 14) continue;
            if (candidate.getInterfaceSubclass() == 1) control = candidate;
            if (candidate.getInterfaceSubclass() == 2) {
                for (int j = 0; j < candidate.getEndpointCount(); j++) {
                    UsbEndpoint ep = candidate.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        stream = candidate; endpoint = ep;
                    }
                }
            }
        }
        if (stream == null || endpoint == null) throw new IllegalStateException("Endpoint video bulk tidak ditemukan");
        if (control != null && !c.claimInterface(control, true)) throw new IllegalStateException("Interface kontrol sedang dipakai aplikasi lain");
        if (!c.claimInterface(stream, true)) throw new IllegalStateException("Interface video sedang dipakai aplikasi lain");
        if (!c.setInterface(stream)) throw new IllegalStateException("Gagal memilih interface video");
        byte[] raw = c.getRawDescriptors();
        int uvcVersion = 0x100, currentSubclass = -1, format = -1, frame = -1, width = 0, height = 0, interval = 333333;
        for (int pos = 0; pos + 2 <= raw.length;) {
            int length = raw[pos] & 255;
            if (length < 2 || pos + length > raw.length) break;
            int type = raw[pos + 1] & 255;
            if (type == 4 && length >= 9) currentSubclass = (raw[pos+5] & 255) == 14 ? raw[pos+6] & 255 : -1;
            if (type == 0x24 && length >= 3) {
                int sub = raw[pos+2] & 255;
                if (currentSubclass == 1 && sub == 1 && length >= 5) uvcVersion = le16(raw,pos+3);
                if (currentSubclass == 2 && sub == 6 && length >= 5) format = raw[pos+3] & 255;
                if (currentSubclass == 2 && sub == 7 && length >= 26 && format > 0) {
                    int w = le16(raw,pos+5), h = le16(raw,pos+7);
                    if (frame < 0 || (w == 1280 && h == 720)) {
                        frame = raw[pos+3] & 255; width = w; height = h;
                        interval = le32(raw,pos+21);
                        if (w == 1280 && h == 720) break;
                    }
                }
            }
            pos += length;
        }
        if (format < 0 || frame < 0) throw new IllegalStateException("Deskriptor MJPEG tidak ditemukan");
        int interfaceId = stream.getId();
        int ctrlLength = uvcVersion < 0x110 ? 26 : (uvcVersion < 0x150 ? 34 : 48);
        byte[] len = new byte[2];
        if (c.controlTransfer(0xa1,0x85,0x100,interfaceId,len,2,1000) == 2) {
            int requested = le16(len,0);
            if (requested >= 26 && requested <= 48) ctrlLength = requested;
        }
        byte[] probe = new byte[ctrlLength];
        c.controlTransfer(0xa1,0x87,0x100,interfaceId,probe,probe.length,1000);
        probe[0] = 1; probe[1] = 0; probe[2] = (byte) format; probe[3] = (byte) frame;
        put32(probe,4,interval);
        transfer(c,0x21,1,0x100,interfaceId,probe);
        transfer(c,0xa1,0x81,0x100,interfaceId,probe);
        transfer(c,0x21,1,0x200,interfaceId,probe);
        int payloadSize = le32(probe,22);
        if (payloadSize < endpoint.getMaxPacketSize() || payloadSize > 4*1024*1024)
            throw new IllegalStateException("Ukuran payload UVC tidak valid: " + payloadSize);
        say("ZV-1 MJPEG " + width + "x" + height + " @ " + (10000000.0 / interval) + " fps, payload=" + payloadSize);
        // Some Android kernels reject large synchronous USBDEVFS_BULK reads.
        // Reassemble a UVC payload across bounded transfers instead.
        byte[] packet = new byte[Math.min(payloadSize,16384)];
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(512*1024);
        int lastFid = -1, errors = 0, payloadBytes = 0, payloadFlags = 0;
        receivedFrames=0; streamStarted=SystemClock.elapsedRealtime();
        long packets=0;
        boolean badFrame = false;
        long lastData=SystemClock.elapsedRealtime();
        c.controlTransfer(0x02,1,0,endpoint.getAddress(),null,0,1000);
        while (running.get()) {
            int n = c.bulkTransfer(endpoint, packet, packet.length, 1000);
            if (n < 0) {
                if (++errors == 1) Log.w(TAG,"bulkTransfer returned "+n+" (request="+packet.length+")");
                if (SystemClock.elapsedRealtime()-lastData > 10000) throw new IllegalStateException("USB tidak mengirim data selama 10 detik");
                SystemClock.sleep(5);
                continue;
            }
            errors = 0;
            lastData=SystemClock.elapsedRealtime();
            if(packets++ < 2) {
                StringBuilder sample=new StringBuilder();
                for(int k=0;k<Math.min(n,12);k++) sample.append(String.format(java.util.Locale.US,"%02x",packet[k]&255));
                Log.i(TAG,"USB read n="+n+" payloadOffset="+payloadBytes+" head="+sample);
            }
            if(n==0) { payloadBytes=0; continue; }
            int header=0;
            if(payloadBytes==0) {
                if(n<2) continue;
                header=packet[0]&255;
                if(header<2 || header>n) { badFrame=true; continue; }
                payloadFlags=packet[1]&255;
                int fid=payloadFlags&1;
                if(fid!=lastFid) {
                    // ZV-1 signals the end of a frame by toggling FID; it does
                    // not necessarily set the optional EOF flag.
                    if(!badFrame && jpeg.size()>4) emit(jpeg);
                    jpeg.reset(); badFrame=false; lastFid=fid;
                }
                if((payloadFlags&0x40)!=0) badFrame=true;
            }
            payloadBytes+=n;
            if (!badFrame && n > header) {
                if (jpeg.size() + n - header > 4*1024*1024) { badFrame = true; jpeg.reset(); }
                else jpeg.write(packet,header,n-header);
            }
            boolean payloadDone=n<packet.length || payloadBytes>=payloadSize;
            if (payloadDone && (payloadFlags & 2) != 0) {
                if (!badFrame && jpeg.size() > 4) {
                    emit(jpeg);
                }
                jpeg.reset(); badFrame = false;
            }
            if(payloadDone) payloadBytes=0;
        }
        c.releaseInterface(stream);
        if (control != null) c.releaseInterface(control);
    }
    private void emit(ByteArrayOutputStream jpeg) {
        byte[] image=jpeg.toByteArray();
        if((image[0]&255)!=255 || (image[1]&255)!=216) return;
        listener.jpeg(image,SystemClock.elapsedRealtimeNanos()/1000);
        receivedFrames++;
        if(receivedFrames==1 || receivedFrames%150==0)
            say("Frame Sony diterima: "+receivedFrames+", rata-rata "+(receivedFrames*1000.0/(SystemClock.elapsedRealtime()-streamStarted+1))+" fps");
    }
    private static void transfer(UsbDeviceConnection c,int type,int request,int value,int iface,byte[] data) {
        int result = c.controlTransfer(type,request,value,iface,data,data.length,1500);
        if (result != data.length) throw new IllegalStateException("UVC control " + request + "/" + value + " result=" + result);
    }
    private void say(String message) { Log.i(TAG,message); listener.status(message); }
    private void fail(String message) { running.set(false); say("ERROR: " + message); }
    @Override public void close() {
        running.set(false);
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
            receiver = null;
        }
        UsbDeviceConnection c = connection;
        if (c != null) c.close();
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private static int le16(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8); }
    private static int le32(byte[] b,int p) { return le16(b,p)|(le16(b,p+2)<<16); }
    private static void put32(byte[] b,int p,int n) { for(int i=0;i<4;i++) b[p+i]=(byte)(n >>> (8*i)); }
}
