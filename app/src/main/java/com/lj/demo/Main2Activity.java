package com.lj.demo;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

import com.lj.ljimageloader.ImageLoader;
import com.lj.ljimageloader.R;

public class Main2Activity extends AppCompatActivity {
    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);
        imageView = findViewById(R.id.image_view);
        ImageLoader.build(Main2Activity.this).place(R.mipmap.ic_launcher).error(R.mipmap.ic_launcher_round).bindBitmap("https://n.sinaimg.cn/front/520/w260h260/20190402/sx2b-hvcmeux5380071.jpg", imageView);
    }
}
