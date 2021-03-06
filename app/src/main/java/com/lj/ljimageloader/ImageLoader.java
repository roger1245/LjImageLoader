package com.lj.ljimageloader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageLoader {
    private int resIdPlace;
    private int resIdError;
    public static final String TAG = "ImageLoader";
//这个参数设置tag其实就是uri
//    private static final int TAG_KEY_URI = 0;

    public static final int MESSAGE_POST_RESULT = 1;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;

    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;


    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;

    private static final int DISK_CACHE_INDEX = 0;

    private boolean mIsDiskLruCacheCreated = false;
    private Context mContext;

    private LruCache<String, Bitmap> mMemoryCache;

    private DiskLruCache mDiskLruCache;

    private ImageResizer mImageResizer = new ImageResizer();




    private static final ThreadFactory sThreadFactory = new ThreadFactory() {

        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), sThreadFactory);


    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            if (imageView.getTag().equals(result.uri)) {
                imageView.setImageBitmap(result.bitmap);
            } else if (resIdError != 0) {
                imageView.setImageResource(resIdError);
            }
        }
    };
    //占位图
    public ImageLoader place(int resId) {
        this.resIdPlace = resId;
        return this;
    }
    public ImageLoader error(int resId) {
        this.resIdError = resId;
        return this;
    }


    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024
;            }
        };
        File disCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!disCacheDir.exists()) {
            disCacheDir.mkdir();
        }
        if (disCacheDir.getUsableSpace() > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(disCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 创建ImageLoader实例
     */
    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }


    //内存缓存的添加和读取过程
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }
    private Bitmap getBitmapFromMemCache(String key) {
        return mMemoryCache.get(key);
    }

    /**
     * 同步加载
     */
    public void bindBitmap(final String uri, final ImageView imageView) {
        bindBitmap(uri, imageView, 0, 0);
    }

    /**
     *
     * 异步加载
     */
    public void bindBitmap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight) {
        imageView.setTag(uri);
        if (resIdPlace != 0) {
            imageView.setImageResource(resIdPlace);
        }
        final Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }
        imageView.setTag(uri);
        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri, reqWidth, reqHeight);
                if (bitmap != null) {
                    LoaderResult result = new LoaderResult(imageView, uri, bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();
                }else if (resIdError != 0) {
                    //显示加载失败图片
                    imageView.setImageResource(resIdError);
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);

    }

    /**
     * 同步加载
     * 无法在主线程中调用
     * 我作死的设置为私有
     */
    private Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            return bitmap;
        }
        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null) {
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
            if (bitmap != null) {
                return bitmap;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!mIsDiskLruCacheCreated) {
            bitmap = downloadBitmapFromUrl(uri);
        }
        return bitmap;
    }

    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = Encrypt.hashKeyFromUrl(url);
        return getBitmapFromMemCache(key);
    }

    /*
     *不能在主线程中调用
     */
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight)
            throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw  new RuntimeException();
        }
        if (mDiskLruCache == null) {
            return null;
        }

        String key = Encrypt.hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException();
        }

        if (mDiskLruCache == null) {
            return null;
        }
        Bitmap bitmap = null;
        String key = Encrypt.hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampleBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
            if (bitmap != null) {
                addBitmapToMemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }
    private int IO_BUFFER_SIZE = 4 * 1024; //4 KB

    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            MyUtil.closeQuietly(in);
            MyUtil.closeQuietly(out);

        }
        return false;
    }
    //这个方法基本用不上
    private Bitmap downloadBitmapFromUrl(String urlString) {
        return null;
    }


    private File getDiskCacheDir(Context context, String uniqueName) {
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }



    private static class LoaderResult {
        ImageView imageView;
        String uri;
        Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }

    }
}
