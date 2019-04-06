package com.lj.ljimageloader;

import java.io.Closeable;

public class MyUtil {
    static void closeQuietly(/*Auto*/Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    private MyUtil() {
    }
}
