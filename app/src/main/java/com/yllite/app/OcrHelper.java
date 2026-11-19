package com.yllite.app;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * ML Kit 中文 OCR(离线内置模型)。输入屏幕截图, 输出文本行/块 + 屏幕包围盒,
 * 用于识别无障碍树不可见的 H5/自绘弹窗(超级福袋任务、领券、开奖结果等)。
 */
public final class OcrHelper {

    public static class Item {
        public final String text;
        public final Rect box;
        public Item(String t, Rect b) { text = t; box = b; }
    }

    public interface Callback { void onResult(List<Item> items); }

    private static TextRecognizer recognizer;

    private static synchronized TextRecognizer get() {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        return recognizer;
    }

    /** 识别位图(需 ARGB_8888 软件位图); 回调在主线程可用的 Executor 上。 */
    public static void recognize(Bitmap bmp, Executor ex, Callback cb) {
        try {
            InputImage img = InputImage.fromBitmap(bmp, 0);
            get().process(img).addOnSuccessListener(ex, t -> {
                List<Item> out = new ArrayList<>();
                for (Text.TextBlock b : t.getTextBlocks()) {
                    String bt = b.getText();
                    Rect br = b.getBoundingBox();
                    if (br != null && bt != null && bt.trim().length() > 0) {
                        out.add(new Item(bt.replace('\n', ' '), br));
                    }
                    for (Text.Line ln : b.getLines()) {
                        String lt = ln.getText();
                        Rect lr = ln.getBoundingBox();
                        if (lr != null && lt != null && lt.trim().length() > 0) {
                            out.add(new Item(lt, lr));
                        }
                    }
                }
                cb.onResult(out);
            }).addOnFailureListener(ex, e -> cb.onResult(new ArrayList<>()));
        } catch (Throwable t) {
            cb.onResult(new ArrayList<>());
        }
    }
}
