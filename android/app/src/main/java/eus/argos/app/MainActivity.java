package eus.argos.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import java.util.HashMap;
import java.util.Map;

/**
 * Contenedor nativo de ARGOS.
 *
 * <p>Toda la aplicación —captura, modelo de fondo, tracking, siluetas, overlay—
 * corre dentro del WebView. Esta clase existe para resolver tres cosas que la
 * página no puede resolver sola.
 *
 * <h3>1. Contexto seguro</h3>
 *
 * Lo habitual en estos envoltorios es {@code loadUrl("file:///android_asset/…")}.
 * Para ARGOS eso no sirve: {@code file://} <b>no</b> es un contexto seguro, así
 * que {@code navigator.mediaDevices.getUserMedia} no existe y la cámara nunca se
 * abre. El síntoma es una pantalla negra sin ningún error visible.
 *
 * <p>La solución es {@link WebViewAssetLoader}, que sirve los mismos assets bajo
 * {@code https://appassets.androidplatform.net/}. El origen es https, el
 * navegador lo considera seguro y la cámara pasa a estar disponible. No hay
 * servidor real detrás: la petición se intercepta y se resuelve desde el APK.
 *
 * <h3>2. Doble permiso de cámara</h3>
 *
 * Hacen falta los dos, y conceder uno solo falla en silencio:
 * <ul>
 *   <li>el permiso de Android, que pide el usuario;</li>
 *   <li>el permiso del WebView, vía {@link WebChromeClient#onPermissionRequest},
 *       que el WebView <b>deniega por defecto</b> aunque el sistema ya lo haya
 *       concedido.</li>
 * </ul>
 *
 * <h3>3. Contenido mixto</h3>
 *
 * La página se sirve por https y el servidor Python opcional de la red local
 * habla por http. Sin permitir contenido mixto, el navegador bloquea esas
 * llamadas y el sinopsis falla con un «Failed to fetch» idéntico al de no tener
 * servidor. Se permite de forma acotada y se documenta en el manifiesto.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String ENTRY = ORIGIN + "/assets/index.html";

    private WebView web;
    @Nullable private PermissionRequest pendingRequest;

    /**
     * Marca la respuesta como aislada entre orígenes.
     *
     * ONNX Runtime solo usa varios hilos de WASM si {@code crossOriginIsolated}
     * es cierto, y eso exige que el documento llegue con COOP y COEP. Sin ellas
     * la inferencia corre en un hilo aunque el teléfono tenga ocho núcleos, que
     * es buena parte de la lentitud que se ve en pantalla.
     *
     * Se usa {@code credentialless} y no {@code require-corp} a propósito: con
     * {@code require-corp} cualquier recurso externo sin CORP queda bloqueado,
     * y esta aplicación se conecta a cámaras públicas de terceros que no envían
     * esa cabecera. {@code credentialless} da el mismo aislamiento dejando pasar
     * las peticiones sin credenciales. Un WebView antiguo que no la entienda la
     * ignora y se queda en un hilo: peor, pero funcionando.
     */
    private static WebResourceResponse isolate(WebResourceResponse res) {
        Map<String, String> headers = res.getResponseHeaders();
        Map<String, String> out = headers == null
                ? new HashMap<String, String>() : new HashMap<>(headers);
        out.put("Cross-Origin-Opener-Policy", "same-origin");
        out.put("Cross-Origin-Embedder-Policy", "credentialless");
        out.put("Cross-Origin-Resource-Policy", "same-origin");
        // Añadir las cabeceras a la respuesta del loader BASTA. Comprobado.
        //
        // Se probó reconstruirla con línea de estado explícita --- 200 OK ---,
        // porque `AssetsPathHandler` usa el constructor de tres argumentos y
        // deja la respuesta sin código, y cabía que la navegación descartara
        // por eso unas cabeceras que el `fetch` sí veía. Medido en el
        // emulador: idéntico, `aislado=false` en los dos casos con
        // `coop=same-origin, coep=credentialless` llegando al documento. Las
        // cabeceras salen de aquí; el WebView no concede el aislamiento con
        // ellas, y eso no se arregla desde esta clase. Se vuelve a la versión
        // corta para no dejar código que aparenta ser la pieza que lo
        // consigue.
        res.setResponseHeaders(out);
        return res;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // El análisis se detiene si la pantalla se apaga: el WebView deja de
        // recibir frames y requestAnimationFrame se congela.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);
        immersive();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                WebResourceResponse res = loader.shouldInterceptRequest(request.getUrl());
                return res == null ? null : isolate(res);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // Todo lo propio se queda dentro; cualquier otra cosa se delega
                // al navegador del sistema en lugar de convertir esta ventana en
                // un navegador de propósito general.
                Uri u = req.getUrl();
                return !ORIGIN.equals(u.getScheme() + "://" + u.getAuthority());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> grantIfAllowed(request));
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                // Los errores de la página acaban en logcat: sin esto, depurar
                // el motor en un dispositivo real es adivinar.
                android.util.Log.d("ARGOS", m.message() + " @" + m.lineNumber());
                return true;
            }
        });

        WebView.setWebContentsDebuggingEnabled(true);
        ensureCameraPermission();
        web.loadUrl(ENTRY);
    }

    // ---------------------------------------------------------------- //

    private void immersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private boolean hasCamera() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureCameraPermission() {
        if (!hasCamera()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    /**
     * Concede al WebView solo lo que el sistema ya autorizó, y solo vídeo.
     * Reenviar {@code request.getResources()} sin filtrar concedería también el
     * micrófono si la página lo pidiera, que ARGOS no necesita.
     */
    private void grantIfAllowed(PermissionRequest request) {
        if (!hasCamera()) {
            pendingRequest = request;
            ensureCameraPermission();
            return;
        }
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                return;
            }
        }
        request.deny();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_CAMERA) return;

        boolean granted = results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;

        if (pendingRequest != null) {
            if (granted) {
                pendingRequest.grant(
                        new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingRequest.deny();
            }
            pendingRequest = null;
        }
        if (!granted) {
            Toast.makeText(this,
                    "Sin permiso de cámara ARGOS no puede analizar nada.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- //

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Detiene temporizadores y captura al pasar a segundo plano: mantener la
        // cámara viva ahí gasta batería para analizar un frame congelado.
        web.onPause();
        web.pauseTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.resumeTimers();
        web.onResume();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
