use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::{Manager, State};

/// 由 Tauri 拉起的 Java 后端子进程。
/// 退出时确保它一起被关闭，避免留下孤儿进程。
/// 同时记住实际使用的端口，供前端查询（端口可能顺延，不能写死 7080）。
pub struct BackendProc {
    pub child: Mutex<Option<Child>>,
    pub port: std::sync::atomic::AtomicU16,
}

#[tauri::command]
fn backend_port(state: State<BackendProc>) -> u16 {
    state.port.load(std::sync::atomic::Ordering::Relaxed)
}

/// 在应用内的独立窗口里打开 dsh 工作台，不走外部浏览器。
/// 已存在则聚焦并把地址换成最新的（token 每次启动都会变）。
#[tauri::command]
async fn open_workbench(app: tauri::AppHandle, url: String) -> Result<(), String> {
    use tauri::{WebviewUrl, WebviewWindowBuilder};

    if url.trim().is_empty() {
        return Err("工作台地址为空".into());
    }

    if let Some(w) = app.get_webview_window("workbench") {
        // 换地址：WebView 里导航到新 URL
        let _ = w.navigate(
            url.parse::<tauri::Url>().map_err(|e| e.to_string())?,
        );
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
        return Ok(());
    }

    let parsed = url.parse::<tauri::Url>().map_err(|e| e.to_string())?;
    let win = WebviewWindowBuilder::new(&app, "workbench", WebviewUrl::External(parsed))
        .title("DSH 工作台")
        .resizable(true)
        .background_color(tauri::window::Color(9, 11, 14, 255))
        .build()
        .map_err(|e| format!("创建工作台窗口失败：{e}"))?;

    let _ = win.set_focus();
    Ok(())
}

fn backend_jar() -> PathBuf {
    // 找 dsh-backend.jar，按命中优先级排：
    //   1. 可执行文件同目录            —— 便携 tar.gz 布局
    //   2. Tauri 资源目录（deb 安装）  —— /usr/lib/DSHA 或 /usr/lib/com.dshlauncher.app
    //   3. AppImage 挂载点下的资源目录 —— $APPDIR/usr/lib/...
    //   4. 项目根目录                  —— 开发态（cargo 原生跑）
    let mut candidates: Vec<PathBuf> = Vec::new();

    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("dsh-backend.jar"));
            // AppImage：真身在 $APPDIR/usr/bin 里，资源在 $APPDIR/usr/lib
            if let Some(appdir) = dir.parent().and_then(|d| d.parent()) {
                candidates.push(appdir.join("usr/lib/dsh-backend.jar"));
                candidates.push(appdir.join("usr/lib/DSHA/dsh-backend.jar"));
                candidates.push(appdir.join("usr/lib/com.dshlauncher.app/dsh-backend.jar"));
            }
        }
    }
    // deb 安装：资源在 /usr/lib/< productName | identifier >
    candidates.push(PathBuf::from("/usr/lib/DSHA/dsh-backend.jar"));
    candidates.push(PathBuf::from("/usr/lib/com.dshlauncher.app/dsh-backend.jar"));
    // 开发态：项目根目录
    candidates.push(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .join("backend/dsh-backend.jar"),
    );

    for c in candidates {
        if c.exists() {
            return c;
        }
    }
    // 都没有就返回开发态路径，让调用方给出「未找到」的日志
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join("backend/dsh-backend.jar")
}

fn spawn_backend() -> (Option<Child>, u16) {
    let jar = backend_jar();
    if !jar.exists() {
        eprintln!("[tauri] 未找到后端 jar：{}", jar.display());
        return (None, 0);
    }
    // 端口被占用时自动顺延，避免上一个退出不干净的进程把启动卡死
    for port in 7080..7100u16 {
        if std::net::TcpStream::connect(("127.0.0.1", port)).is_ok() {
            eprintln!("[tauri] 端口 {} 已被占用，尝试下一个", port);
            continue;
        }
        match Command::new("java")
            // 有些环境（容器/受限会话）限制了 JVM 的虚拟内存，
            // 给个显式上限，避免 metaspace 预留失败
            .arg("-Xmx192m")
            .arg("-XX:MaxMetaspaceSize=128m")
            .arg("-jar")
            .arg(&jar)
            .arg("--port")
            .arg(port.to_string())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
        {
            Ok(child) => {
                eprintln!("[tauri] 后端已启动，端口 {}", port);
                return (Some(child), port);
            }
            Err(e) => eprintln!("[tauri] 后端启动失败：{}", e),
        }
    }
    (None, 0)
}

#[tauri::command]
fn backend_alive(state: State<BackendProc>) -> bool {
    let mut g = state.child.lock().unwrap();
    match g.as_mut() {
        Some(c) => match c.try_wait() {
            Ok(Some(_)) => false,
            Ok(None) => true,
            Err(_) => false,
        },
        None => false,
    }
}

#[tauri::command]
fn restart_backend(state: State<BackendProc>) -> bool {
    let taken = state.child.lock().unwrap().take();
    shutdown_backend(taken);
    let (child, port) = spawn_backend();
    state.port.store(port, std::sync::atomic::Ordering::Relaxed);
    *state.child.lock().unwrap() = child;
    port != 0
}

/// 优雅关闭后端：先 SIGTERM 让 JVM 跑完 shutdown hook（它会带走 dsh 子进程树），
/// 超时才 SIGKILL。直接 kill 会跳过 hook，留下孤儿 node 进程。
fn shutdown_backend(child: Option<Child>) {
    let Some(mut c) = child else { return };

    // SIGTERM
    #[cfg(unix)]
    {
        let pid = c.id() as i32;
        unsafe { libc::kill(pid, libc::SIGTERM) };
    }
    #[cfg(not(unix))]
    {
        let _ = c.kill();
    }

    // 等它自己退干净（后端 hook 里最多等约 3 秒）
    for _ in 0..40 {
        match c.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) => std::thread::sleep(std::time::Duration::from_millis(100)),
            Err(_) => return,
        }
    }

    // 兜底强杀
    let _ = c.kill();
    let _ = c.wait();
}

pub fn run() {
    let (child, port) = spawn_backend();

    let app = tauri::Builder::default()
        .manage(BackendProc {
            child: Mutex::new(child),
            port: std::sync::atomic::AtomicU16::new(port),
        })
        .invoke_handler(tauri::generate_handler![
            backend_alive,
            restart_backend,
            backend_port,
            open_workbench
        ])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                if let Some(state) = window.try_state::<BackendProc>() {
                    let taken = state.child.lock().unwrap().take();
                    shutdown_backend(taken);
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("Tauri 启动失败");

    app.run(|handle, event| {
        // 主事件循环退出（关窗 / 退出应用）时再兜一次，避免漏网
        if let tauri::RunEvent::Exit = event {
            if let Some(state) = handle.try_state::<BackendProc>() {
                let taken = state.child.lock().unwrap().take();
                shutdown_backend(taken);
            }
        }
    });
}
