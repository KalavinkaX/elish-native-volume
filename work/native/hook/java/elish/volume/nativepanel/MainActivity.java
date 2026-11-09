package elish.volume.nativepanel;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Setup instructions only. This activity has no root, audio or overlay code. */
public final class MainActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text=new TextView(this);
        int padding=(int)(24*getResources().getDisplayMetrics().density);
        text.setPadding(padding,padding,padding,padding);text.setTextSize(17);
        text.setText("Elish 原生应用音量\n3.1.0-key-test1\n\n"
            +"1. 安装同版本 Magisk 模块。\n"
            +"2. 在 LSPosed 启用本模块，作用域勾选系统界面（com.android.systemui）及系统框架 / Android（android）。\n"
            +"3. 关闭旧版 Elish 音量键面板无障碍服务，重启平板。\n"
            +"4. 播放音乐，按音量键，再点击系统音量窗口的更多按钮。\n\n"
            +"应用滑块出现在系统展开面板中。本应用没有悬浮窗或 root 授权请求。\n\n"
            +"仅适配 lineage-24.0-20261013-UNOFFICIAL-elish 的 SystemUI。新增扬声器媒体音量键长按加速，单按与0–10档保持精细。长按800ms后2档，1800ms后3档；新版需设备验证。\n\n"
            +"若没有应用滑块，请导出 LSPosed 模块日志，以及 Magisk 模块 Action 的状态输出。此页面不检测 Hook 是否已经加载。");
        setContentView(text);
    }
}
