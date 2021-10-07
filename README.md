# Decoding H.264 with Android API
This sample is about decoding h264 flux with Android API.

## H.264 flux
The h264 data consists of a multitude of NAL Units (NALU). Each NAL Unit start with a `0x00 0x00 0x00 0x01` or `0x00 0x00 0x01` sequence of bits (depending on the encoder). Them contain either meta-data relative to the h264 format, either partial frame or a full picture.

In our case the h264 data is writted in a file and streamed throug the TCP/IP protocol. 

## Android MediaCodec
In the Android part we must do three things:
- Add a [`SurfaceView`](https://developer.android.com/reference/android/view/SurfaceView) to our layout.
- Implement [`SurfaceHolder.Callback`](https://developer.android.com/reference/android/view/SurfaceHolder.Callback) to handle some event relative to the SurfaceView.
- Instantiate a [`Mediacodec`](https://developer.android.com/reference/android/media/MediaCodec) object, and feed its buffers with NAL Unit (**whitout** the `0x00 0x00 0x00 0x01` sequence).

The Mediacodec work like this: You have two things to do in parallel. One is request for empty buffer and fill it, and the second is waiting for decoded frames and trigger its render. These two parts use blocking function, so we must use two thread (runnable, asynkTask or assimillable).

### More about the MediaCodec
I use [`createDecoderByType(String type)`](https://developer.android.com/reference/android/media/MediaCodec#createDecoderByType(java.lang.String)) to instatiate the MediaCodec. Is not the best way, see the note in Android documentation. Then `configure (MediaFormat format, Surface surface, int flags, MediaDescrambler descrambler)` the MediaCodec, passing it a MediaFormat created with `createVideoFormat (String mime, int width, int height)` (where `mime` can be `"video/avc"`), a `Surface` getting from the `SurfaceView` and the rest can be `null`

You have to `start()` the MediaCodec and fill their buffers's using `dequeueInputBuffer (long timeoutUs)` to obtain the ID/index of a free buffer and get it using `getInputBuffer (int index)`\*.\
When the buffer is filled with the NAL Unit (**whitout** the `0x00 0x00 0x00 0x01` sequence) you can `queueInputBuffer (int index, int offset, int size, long presentationTimeUs, int flags)`\*\* for the MediaCodec proceed it.\
Because `dequeueInputBuffer (long timeoutUs)` is blocking you must do this in a separate thread.\
\**(in older api you must `getInputBuffers()` (the array of buffers) `dequeueInputBuffer (long timeoutUs)` and `clear()` it before writting)*\
\*\**(in our case the parameter `long presentationTimeUs` is not used, this information is find by the MediaCodec in a NAL Unit, set it to `-1`, and flags is not used too, set it to `0`)*

In parallel you have to `dequeueOutputBuffer (MediaCodec.BufferInfo info, long timeoutUs)`. These function is bloking and return an `int` which can be a sepcial value (see the doc) indicate some stats of the Mediacodec (like the numbers of outBuffers has changed, ...) or an index of a buffer ready to be automatically rendered to the `SurfaceView` by call to `releaseOutputBuffer (int index, boolean render)`.\
Because `dequeueOutputBuffer (MediaCodec.BufferInfo info, long timeoutUs)` is blocking you must do this in a separate thread.

Don't forget to `stop()` and `release()` the MediaCodec.

## Difficulty
The hard part, it's to retreive the NAL Unit. In our case the h264 data is streamed with socket so **we can't garantee that the socket's buffer is filled with complete NAL Unit**.\
In fact, the last bits containing in the socket's buffer can be a part of the start sequence of the **next** NAL Unit. For example the buffer's socket can end with `0x00 0x00 0x00` and the missing `0x01` will be the first bit read at the next reading of the socket buffer. **So we must retain the last bits** (size of the start sequence -1\*) and include them in the next read step (in my case the last bits are copied at the start of the buffer and bits read from the socket copied just after).

\**-1 because in the worst case (case when we have to retain the maximum of bits) is missing only the last bit. So, at maximum, we have to keep size of the start sequence -1*

**Don't forget the manifest file we must declare the internet permission `<uses-permission android:name="android.permission.INTERNET" />`.**

## The Client
I propose a basic Python script who send the sample as client. Yon can send the entire file directly in one time or by aleatory sized part or "manually" using the Python shell.

## The Sample
The h264 sample is recorded from a Raspberry with the [picamera lib](https://picamera.readthedocs.io/en/release-1.13/) or from the command line `raspivid -o Desktop/video.h264`

## See
https://yumichan.net/video-processing/video-compression/introduction-to-h264-nal-unit/
https://en.wikipedia.org/wiki/Network_Abstraction_Layer
