/**
 * The {@link android.media.MediaCodec} waits until a sufficient number of these buffers are filled with NAL Unit by the {@link com.example.h264.MainActivity.H264ParseTask} thread.
 * In parallel the {@link com.example.h264.MainActivity.H264RenderTask} thread waits until frames are decoded and triggers their render on the {@link android.view.SurfaceView}.
 * @see https://en.wikipedia.org/wiki/Network_Abstraction_Layer
 * @see https://yumichan.net/video-processing/video-compression/introduction-to-h264-nal-unit/
 */
package com.example.h264;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

//import android.annotation.SuppressLint;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.LinkedList;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    final int PORT = 54596;

    MediaCodec m;
    SurfaceView sv;
    ServerSocket ss;
    Socket s;
    H264RenderTask r;
    H264ParseTask p;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sv = findViewById(R.id.surfaceView);
            sv.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                Log.v("aaa", "mediacodec creation");
                MediaCodecList l = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                m = MediaCodec.createByCodecName(l.findDecoderForFormat(MediaFormat.createVideoFormat("video/avc", sv.getWidth(), sv.getHeight())));
                m.configure(MediaFormat.createVideoFormat("video/avc", sv.getWidth(), sv.getHeight()), sv.getHolder().getSurface(), null, 0);
                Log.v("aaa", "mediacodec created");
            } else {
                Log.v("aaa", "mediacodec creation");
                m = MediaCodec.createDecoderByType("video/avc");
                m.configure(MediaFormat.createVideoFormat("video/avc", sv.getWidth(), sv.getHeight()), sv.getHolder().getSurface(), null, 0);
                Log.v("aaa", "mediacodec created");
            }

            if(android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2){
                MediaCodecInfo i = m.getCodecInfo();
                Log.v("aaa", i.getName());
            }

            m.start();

            /* This anonymous thread waits for a connection then start the two another thread needed for respectively feed the codec (H264ParseTask) and draw decoded frames (H264RenderTask).
            This thread is necessary because ServerSocket#accept() is blocking and we cannot block the main thread. */
            new Thread(){
                @Override
                public void run() {
                    try {
                        ss = new ServerSocket(PORT, 0);
                            Log.v("aaa", "Waiting for connection");
                        s = ss.accept(); // Blocking!
                            Log.v("aaa", "Connection accepted");
                            s.setSoTimeout(10000); // set timeout for all blocking methods. That allow thread to be .interrupt() correctly when their nothing to .read()
                        p = new H264ParseTask(m, s.getInputStream());
                            p.start();
                            Log.v("aaa", "H264ParseTask started");
                        r = new H264RenderTask(m, sv);
                            r.start();
                            Log.v("aaa", "H264RenderTask started");
                    } catch (IOException e) {
                        Log.v("aaa", "Socket closed or Error while waiting for a connection");
                        e.printStackTrace();
                    }
                }
            }.start();

        } catch (IOException e) {
            Log.v("aaa", "cannot create or configure the media codec");
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.v("aaa", "surfaceChanged()");
        // TODO when surface change...
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v("aaa", "surfaceDestroyed()");

        try {
            if (p != null) {
                p.interrupt();
                p.join();
            }
            if (r != null) {
                r.interrupt();
                r.join(); // important because of some blocking function in the thread. Because of its timeouts, they cause call of #Mediacodec object whereas it is immediately deleted at the next instruction without .join() call
            }
        } catch(Exception e) {
            e.printStackTrace();
        }

        m.stop();
        m.release();
    }

    @Override
    protected void onDestroy() {
        if(s != null)
            try { s.close(); } catch (IOException e) { Log.v("aaa", "cannot close client socket"); e.printStackTrace(); }
        if(ss != null)
            try { ss.close(); } catch (IOException e) { Log.v("aaa", "cannot close server socket"); e.printStackTrace(); }

        super.onDestroy();
        Log.v("aaa", "app end");
    }

    /**
     * This thread extracts NAL unit and fills codec's buffers with it.
     * Each codec's buffer must be fill with a correct NAL unit (without the NAL start sequence, {@link <a href='https://yumichan.net/video-processing/video-compression/introduction-to-h264-nal-unit/'> wich is 0x000001 or 0x00000001</a>}).
     * It queries an available buffer with {@link MediaCodec#dequeueInputBuffer(long)}, fills it, and returns it to the MediaCodec with {@link MediaCodec#queueInputBuffer(int, int, int, long, int)}.
     * The parameter presentationTimeUS of the {@link MediaCodec#queueInputBuffer(int, int, int, long, int)} is useless in our case, the codec finds this information in the NAL unit.
     * The codec must be started with {@link MediaCodec#start()} before this thread start.
     */
    private class H264ParseTask extends Thread {
        MediaCodec m;
        InputStream in;

        /**
         * @param m is the {@link MediaCodec} instance
         * @param in is the {@link InputStream} filled with h264 data
         */
        public H264ParseTask(MediaCodec m, InputStream in) { // TODO add param nal_seq,capacity
            this.m = m;
            this.in = in;
        }

        @Override
        public void run() {
            Log.v("aaa", "H264ParseTask is running");
            // Socket relative
            int read = 0;                                           // number of bits read by InputStream#read()
            int cum = 0;                                            // cumulative bits read

            // MediaCodec's buffer relative
            ByteBuffer[] buffers;                                   // buffers used by the MediaCodec class getting from MediaCodec#getInputBuffers()
            int buffindex = -1;                                     // the index of the current free buffer getting from MediaCodec#dequeueInputBuffer()
            ByteBuffer current_bb;                                 // Since LOLLIPOP MediaCodec#getInputBuffer(buffindex) replace MediaCodec#getInputBuffers()

            // Java's #Buffer like see https://docs.oracle.com/javase/7/docs/api/java/nio/Buffer.html
            // Java's #Buffer is not used because we cannot read many bytes as array easily...
            int capacity = 10240;                                   // capacity of the buffer
            int limit = 0;                                          // is the index of the first element that should not be read or written. like #ByteBuffer.limit
            byte[] bb = new byte[capacity];                         // the buffer used to parse data from the InputStream
            int from = 0;                                           // used for copying NAL unit from the parse buffer into Mediacodec buffers
            int to;                                                 // used for copying NAL unit from the parse buffer into Mediacodec buffers

            //byte[] see = new byte[capacity]; // used for debug

            // NAL unit parser relative
            byte[] nal_seq = new byte[]{0x00, 0x00, 0x00, 0x01};    // the NAL unit start sequence, it can be 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01 depending of the encoder

            try {
                /* Getting buffers for android < LOLLIPOP */
                buffers = m.getInputBuffers();                      // get MediaCodec's buffer array
                    if(buffers.length < 1) {
                        Log.v("aaa", "MediaCodec has not return any buffers");
                        return; // end thread
                    }

                /* Dequeue a buffer*/
                buffindex = m.dequeueInputBuffer(1000000);
                    Log.v("aaa", "dequeu "+buffindex);

                /* Get the dequeued buffer */
                if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
                    current_bb = m.getInputBuffer(buffindex);
                else
                    current_bb = buffers[buffindex];

                do {
                    /* Reading */
                    read = in.read(bb, limit, capacity - limit);// add bytes read after the remaining byte (ie: can be an hypothetical incomplete NAL unit start sequence)
                    if(read == -1)                                  // read == -1 when the InputStream reach eof (when the socket is .close())
                        break;                                      // end of the thread

                    Log.v("aaa", "read "+read+"from "+limit+"to "+(limit+read));
                    limit += read;                                  // update the limit of the buffer
                    cum += read;                                    // Accumulate bits read

                    if(limit < nal_seq.length)                      // if read bits are smaller than the NAL unit start sequence (0x00 0x00 0x00 0x01) continue to add bit in the buffer
                        continue; // cancel the current iteration and start the next one

                    /* Parsing */
                    from = 0;
                    to = find(bb, nal_seq, from, limit);            // Search a NAL unit start sequence from 0
                    while(to != -1) {                               // if a NAL unit is find
                        current_bb.put(bb, from, to - from); // copy bytes from the previous NAL unit start sequence to the new one in the current buffer
                            //Arrays.fill(see, (byte) 2);
                            //System.arraycopy(bb, from, see, 0, to - from);
                            Log.v("aaa", "copy from " + from + "to " + (from + to - from));

                        m.queueInputBuffer(buffindex, 0, current_bb.limit(), 0, 0); // queue the buffer
                            Log.v("aaa", "queu " + buffindex);

                        buffindex = m.dequeueInputBuffer(1000000);                                  // dequeue new one
                            Log.v("aaa", "dequeu " + buffindex);

                        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)     // getting it
                            current_bb = m.getInputBuffer(buffindex);
                        else
                            current_bb = buffers[buffindex];

                        from = to;                                                                      // memorise the position of the NAL sequence
                        to = find(bb, nal_seq, from+nal_seq.length, limit);                        // search for the next NAL unit
                    }
                    // copy from the last NAL unit to the end of buffer, but not the (nal_seq.length-1) bytes in case of their are part of an incomplete NAL
                    to = limit - (nal_seq.length-1);
                    if (from < to) {
                        current_bb.put(bb, from, to - from);
                        //Arrays.fill(see, (byte) 2);
                        //System.arraycopy(bb, from, see, 0, to - from);
                        Log.v("aaa", "copy all from " + from + "to " + (from + (to - from)));
                    }

                    // move the remaining hypothetical incomplete NAL (nal_seq.length-1 bytes) to the beginning of the buffer.
                    // At the next step of the while, read() will write after them and hypothetically complete the sequence which will be found by find()
                    System.arraycopy(bb, limit - (nal_seq.length-1), bb, 0, (nal_seq.length - 1)); // like #ByteBuffer.compact()
                        //Arrays.fill(see, (byte) 2);
                        //System.arraycopy(bb, limit - (nal_seq.length-1), see, 0, (nal_seq.length - 1));
                        Log.v("aaa", "remaining from "+(limit - (nal_seq.length-1)));
                    limit = (nal_seq.length - 1);
                } while (!isInterrupted());

            } catch (SocketTimeoutException e) {
                // if timeout occur interrupt the thread
                Log.v("aaa", "socket timeout, nothing to read");
                e.printStackTrace();
            } catch (IOException e) {
                // a networking error is occurred. Like connexion lose. (cable disconnected or something else. not the socket close())
                Log.v("aaa", "IOException"+e.getMessage());
                e.printStackTrace();
            } catch (IllegalStateException e) {
                // the MediaCodec is not in the good state
                Log.v("aaa", "IllegalStateException"+e.getMessage());
                e.printStackTrace();
            } /*catch (MediaCodec.CodecException e) { // REQUIRE API 21
                Log.v("aaa", "MediaCodec.CodecException"+e.getMessage());
            }*/ catch(Exception e) {
                Log.v("aaa", e.getClass().getCanonicalName()+e.getMessage());
                e.printStackTrace();
            }

            if(current_bb != null)
                m.queueInputBuffer(buffindex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

            Log.v("aaa", "H264ParseTask end");
        }
    }

    /**
     * This thread allow decoded frames to be rendered on the {@link SurfaceView}.
     * It queries an output buffer containing a decoded frame with {@link MediaCodec#dequeueOutputBuffer(MediaCodec.BufferInfo, long)} and triggers the render of the frame into the surface view by releasing the buffer to the MediaCodec with {@link MediaCodec#releaseOutputBuffer(int, boolean)}.
     * The codec must be started whit {@link MediaCodec#start()} before this thread start.
     */
    private class H264RenderTask extends Thread {
        MediaCodec m;
        SurfaceView sv;

        public H264RenderTask(MediaCodec m, SurfaceView sv) {
            this.m = m;
            this.sv = sv;
        }

        //@SuppressLint("WrongConstant") // TODO test with old version of android studio
        @Override
        public void run() {
            Log.v("aaa", "H264RenderTask is running");

            int bufferindex = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean done = false;

            do {
                try {
                    bufferindex = m.dequeueOutputBuffer(info, 1000000); // the method return either a special number or a buffer index
                    switch (bufferindex) {
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            //Log.v("aaa", "no output available yet");
                            break;
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            /* this is not use in our case */
                            //Log.v("aaa", "info changed");
                            //Log.v("aaa", "buffers.length = "+m.getOutputBuffers().length); // get the new lengths
                            //encodeOutputBuffers = mDecodeMediaCodec.getOutputBuffers();
                            break;
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            //mediaformat changed
                            Log.v("aaa", "format changed : "+m.getOutputFormat().toString());
                            break;
                        default:    // if it is not a special buffer index we can use it like a regular index.
                            //Log.v("aaa", "frame release");
                            m.releaseOutputBuffer(bufferindex, true);
                            //Log.v("aaa", "frame release");
                            if((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.v("aaa", "Last buffer released");
                                done = true;
                            }
                    }
                } catch (IllegalStateException e) {
                    Log.v("aaa", "H264RenderTask ILLEGAL STATS");
                    e.printStackTrace();
                    break;
                }
            } while(!isInterrupted() || !done);

            // Hide the SurfaceView. For example...
            /*runOnUiThread(new Runnable() {
               @Override
               public void run() {
                   sv.setVisibility(View.GONE);
               }
            });*/

            Log.v("aaa", "H264RenderTask end");
        }
    }

    /**
     * {@link String#indexOf(String, int)} like. Search {@code key} in {@code buffer} start at {@code start} end at {@code end} like [{@code start}:{@code end}[ ({@code end} is exclude).
     * @param buffer where to search the bits sequence
     * @param key bits sequence to find
     * @param start offset to start the search
     * @param end is the index that should not be read
     * @return index where {@code key} was found from the beginning of {@code buffer}
     */
    static private int find(byte[] buffer, byte[] key, int start, int end) {
        if (!(end <= buffer.length && end >=0 ))
            return -1;
        if(!(start < buffer.length && start >= 0))
            return -1;

        end -= (key.length-1); // Optimisation: is useless to search if there is not enough byte left. Security : The while below can't overflow when it iterate through the key

        for (int i = start; i < end; ++i) {
            int j=0;
            for (; j < key.length && buffer[i + j] == key[j]; ++j);
            if (j == key.length)
                return i;
        }
        return -1;
    }

    static private LinkedList<Integer> findAll(byte[] buffer, byte[] key, int start, int end) {
        LinkedList<Integer> l = new LinkedList<>();
        for(int p = start; ; p += key.length) {
            p = find(buffer, key, p, end);
            if (p == -1)
                break;
            l.add(p);
        }
        return l;
    }
}

