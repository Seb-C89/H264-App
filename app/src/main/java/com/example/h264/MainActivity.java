/**
 * The {@link android.media.MediaCodec} wait until a sufficient number of these buffer's are filled with NAL Unit by the {@link com.example.h264.MainActivity.H264ParseTask}.
 * In parallel the {@link com.example.h264.MainActivity.H264RenderTask} wait until frames are decoded and trigger their render on the {@link android.view.SurfaceView}.
 * @see https://en.wikipedia.org/wiki/Network_Abstraction_Layer
 * @see https://yumichan.net/video-processing/video-compression/introduction-to-h264-nal-unit/
 */
package com.example.h264;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import android.media.MediaCodec;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

// TODO do something when stream end.

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

        Log.v("aaa", "app start");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            m = MediaCodec.createDecoderByType("video/avc");
            m.configure(MediaFormat.createVideoFormat("video/avc", sv.getWidth(), sv.getHeight()), sv.getHolder().getSurface(), null, 0);
            m.start();
            //m.configure(MediaFormat.createVideoFormat("video/mp4v-es", sv.getWidth(), sv.getHeight()), sv.getHolder().getSurface(), null, 0);
        } catch (IOException e) {
            Log.v("aaa", "cannot create or configure the media codec");
            e.printStackTrace();
        }

        /* This anonymous thread wait for a connection then start the two another thread needed for respectively feed the codec and draw decoded frames.
        This thread is necessary because ServerSocket#accept() is blocking and you cannot block the main thread. */
        new Thread(){
            @Override
            public void run() {
                try {
                    ss = new ServerSocket(PORT, 0);
                        Log.v("aaa", "Waiting for connection");
                    s = ss.accept();
                        s.setSoTimeout(5000); // allow thread to be .interrupt() correctly when .read() can block indefinitely
                    p = new H264ParseTask(m, s.getInputStream());
                        p.start();
                    r = new H264RenderTask(m, sv);
                        r.start();
                } catch (IOException e) {
                    Log.v("aaa", "Error while waiting for a connection");
                    e.printStackTrace();
                }
            }
        }.start();
        Log.v("aaa", "surfaceCreated()");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.v("aaa", "surfaceChanged()");
        // TODO when surface change...
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v("aaa", "surfaceDestroyed()");

        if(r != null)
            r.interrupt();
        if(p != null)
            p.interrupt();

        m.stop();
        m.release();
    }

    @Override
    protected void onDestroy() { // TODO
        if(s != null)
            try { s.close(); } catch (IOException e) { Log.v("aaa", "cannot close client socket"); e.printStackTrace(); }
        if(ss != null)
            try { ss.close(); } catch (IOException e) { Log.v("aaa", "cannot close server socket"); e.printStackTrace(); }

        super.onDestroy();
        Log.v("aaa", "app end");
    }

    /**
     * This thread extract NAL unit and fill codecs buffer's with it.
     * Each codecs buffer's must be fill with a correct NAL unit (without the NAL start sequence, {@link <a href='https://yumichan.net/video-processing/video-compression/introduction-to-h264-nal-unit/'> wich is 0x000001 or 0x00000001</a>}).
     * Is query an available buffer with {@link MediaCodec#dequeueInputBuffer(long)}, fill it, and return it to the MediaCodec with {@link MediaCodec#queueInputBuffer(int, int, int, long, int)}.
     * The parameter presentationTimeUS of the {@link MediaCodec#queueInputBuffer(int, int, int, long, int)} is useless in our case, the codec find this information in the NAL unit.
     * The codec must be started with {@link MediaCodec#start()} before this thread start.
     */
    private class H264ParseTask extends Thread {
        MediaCodec m;
        InputStream in;

        /**
         * @param m is the {@link MediaCodec} instance
         * @param in is the {@link InputStream} which is fill with h264 data
         */
        public H264ParseTask(MediaCodec m, InputStream in) { // TODO add param sequence, buffer size
            this.m = m;
            this.in = in;
        }

        @Override
        public void run() {
            Log.v("aaa", "H264ParseTask is running");
            // Socket relative
            int read = 0;                                           // number of bits read by InputStream#read()
            int cum = 0;                                            // mathematical accumulation of this.read. Represent the total bits received

            // MediaCodec buffer's relative
            ByteBuffer[] buffers;                                   // buffers used by the MediaCodec class and get from it
            int buffindex = -1;                                     // the index of the current free buffer getting from MediaCodec#dequeueInputBuffer()

            // Java buffer's like see https://docs.oracle.com/javase/7/docs/api/java/nio/Buffer.html
            // Java buffer cannot be use because you cannot write directly in it array
            // buffer to store read bits.
            int capacity = 1024;                                    // capacity of the buffer
            int cursor = 0;                                         // is the index of the next element to be read or written
            int limit = 0;                                          // is the index of the first element that should not be read or written
            byte[] bb = new byte[capacity];                         // a buffer used to parse data from the InputStream

            // NAL unit parser relative
            byte[] nal_seq = new byte[]{0x00, 0x00, 0x00, 0x01};    // the NAL unit start sequence, it can be 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01 depending of the encoder
            int nal_pos = -1;                                       // position of the current founded NAL unit start sequence (0x0000001)


            try {
                buffers = m.getInputBuffers();                      // get MediaCodec buffer array

                // Read bits until the buffer is filled at least with enough bits for search a NAL unit start sequence (0x0000001)
                do {
                    try {
                        read = in.read(bb, limit, capacity-limit);  // read bits from the InputStream and add it in the parse buffer from limit to the end of it
                    } catch (SocketTimeoutException e) {
                        // if timeout occur continue until the thread is interrupted
                        continue;
                    }

                    if(read == -1)                                  // read == -1 when the InputStream reach eof (when the socket is .close())
                        break;

                    limit += read;                                  // update the limit of the buffer
                    cum += read;                                    // Accumulate read bits

                    if(limit < nal_seq.length)                      // if read bits are smaller than the NAL unit start sequence (0x0000001) pass and continue to add bit in the buffer
                        continue;

                    //---------------------------------------------------------------------------------------------------------------------------------------------------------------------

                    cursor = 0;
                    // read all bit to the next NAL unit start sequence (0x0000001) and fill the current buffer with it, until the buffer limit is reach
                    do {
                        nal_pos = find(bb, nal_seq, cursor, limit); // search a NAL unit start sequence (0x0000001) in the parse buffer
                        if(nal_pos < 0) {                                                                           // IF THE NAL UNIT START SEQUENCE (0x0000001) IS NOT FIND
                            if(buffindex > -1){                                                                     // if a MediaCodec's buffer is available
                                buffers[buffindex].put(bb, cursor, limit - (nal_seq.length - 1) - cursor);   // Write all the parse buffer in the current buffer excepted the (nal_seq.length - 1) bit because their can be a part of an incomplete NAL unit start sequence (0x0000001) |...-0x00-0x00-0x00| and the next packet will read |0x01-...|
                            }                                                                                       // else do nothing, these bits will be overwrite by new ones
                            cursor = limit-(nal_seq.length-1);                                                      // put the cursor on the last position read
                        }
                        else {                                                                                                    // IF THE NAL UNIT START SEQUENCE (0x0000001) IS FIND
                            if(buffindex > -1){                                                                                   // if a MediaCodec's buffer is available
                                buffers[buffindex].put(bb, cursor, nal_pos - cursor);                                      // Write bits until the NAL unit start sequence (0x0000001) in the buffer
                                m.queueInputBuffer(buffindex, 0, buffers[buffindex].limit(), 0, 0); // send it. (presentationTimeUS must be >0 (-1 cause crash) but seem to be not used...)
                            }
                            buffindex = m.dequeueInputBuffer(-1); buffers[buffindex].clear();                           // query new one
                            cursor = nal_pos + nal_seq.length;                                                                    // put the cursor on the last position read
                        }
                    } while (cursor < limit-(nal_seq.length-1));                                                    // read until there is less than the size of a NAL unit start sequence (0x0000001) in the parse buffer

                    System.arraycopy(bb, cursor, bb, 0, limit-cursor); limit = limit-cursor;        // move the the remaining bits to the start of the parse buffer and update its limit

                } while (!Thread.interrupted() & limit >= 0);

            } catch (IOException e) {
                // a networking error is occurred. Like connexion lose. (like cable disconnected or something else. not the socket close())
                Log.v("aaa", "IOException"+e.getMessage());
                e.printStackTrace();
            } catch (IllegalStateException e) {
                // the MediaCodec is not in the good state
                Log.v("aaa", "IllegalStateException"+e.getMessage());
                e.printStackTrace();
            } /*catch (MediaCodec.CodecException e) {
                Log.v("aaa", "MediaCodec.CodecException"+e.getMessage());
            }*/ catch(Exception e) {
                Log.v("aaa", e.getClass().getCanonicalName()+e.getMessage());
                e.printStackTrace();
            }

            Log.v("aaa", "H264ParseTask end");
        }
    }

    /**
     * This thread allow decoded frames to be rendered on the {@link SurfaceView}.
     * Is query an output buffer containing a decoded frame with {@link MediaCodec#dequeueOutputBuffer(MediaCodec.BufferInfo, long)} and trigger the render of the frame into the surface view by releasing the buffer to the MediaCodec with {@link MediaCodec#releaseOutputBuffer(int, boolean)}.
     * The codec must be started whit {@link MediaCodec#start()} before this thread start.
     */
    private class H264RenderTask extends Thread {
        MediaCodec m;
        SurfaceView sv;

        public H264RenderTask(MediaCodec m, SurfaceView sv) {
            this.m = m;
            this.sv = sv;
        }

        @SuppressLint("WrongConstant")
        @Override
        public void run() {
            Log.v("aaa", "H264RenderTask is running");

            int bufferindex = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            do {
                try {
                    bufferindex = m.dequeueOutputBuffer(info, 5000); // the method return either a special number or a buffer index //@SuppressLint("WrongConstant")
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
                            //Log.v("aaa", "format changed");
                            //mediaformat changed
                            break;
                        default:    // if it is not a special buffer index you can use it like a regular index.
                            m.releaseOutputBuffer(bufferindex, true);
                    }
                } catch (IllegalStateException e) {
                    // most time because H264ParseTask end and stop the decoder, causing this exception and stop this thread.
                    e.printStackTrace();
                    break;
                }
            } while(!isInterrupted());

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
     * {@link String#indexOf(String, int)} like. Search {@code key} in {@code buffer} start at {@code start} end at {@code end}.
     * @param buffer where to search the bits sequence
     * @param key bits sequence to find
     * @param start offset for start
     * @param end limit to end
     * @return
     */
    static private int find(byte[] buffer, byte[] key, int start, int end) {
        end = (end < buffer.length && end >=0 ) ? end : buffer.length;
        start = (start < buffer.length && start >= 0) ? start : 0;

        for (int i = start; i <= end - (key.length-1); ++i) { // TODO check "i < end" and "key.length +1" OK
            int j=0;
            for (; (i+j) < end && j < key.length && buffer[i + j] == key[j]; ++j);
            if (j == key.length)
                return i;
        }
        return -1;
    }
}

