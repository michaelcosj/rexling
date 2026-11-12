//! Run only against a disposable Rex server selected by REX_TEST_SERVER.
use rexling_core::{
    control::{Control, Trace},
    data::Data,
    ghostty::Replica,
    transport::Endpoint,
};
use serde_json::json;
use std::time::Duration;
#[tokio::test(flavor = "current_thread")]
#[ignore = "requires an isolated Rex server"]
async fn images_stream_and_restore_from_the_real_rex_server() {
    let server = std::env::var("REX_TEST_SERVER").unwrap();
    let endpoint = Endpoint::new(&server, Duration::from_secs(10)).unwrap();
    let mut control = Control::connect(endpoint.clone(), None, Trace::default())
        .await
        .unwrap();
    let file = std::env::temp_dir().join(format!("rexling-kitty-{}.png", std::process::id()));
    {
        let mut encoder = png::Encoder::new(std::fs::File::create(&file).unwrap(), 2, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&[0, 0, 255, 255, 0, 255, 0, 255])
            .unwrap();
    }
    let script = format!(
        r#"import base64,sys,time
print('GRAPHICS_START',flush=True)
time.sleep(1)
sys.stdout.write('\x1b_Ga=T,f=32,s=2,v=1,i=7,c=2,r=1;/wAA/wD/AP8=\x1b\\');sys.stdout.flush()
time.sleep(1)
p=base64.b64encode({:?}.encode()).decode()
sys.stdout.write('\x1b_Ga=T,f=100,t=f,i=8,c=2,r=1;'+p+'\x1b\\');sys.stdout.flush()
print('GRAPHICS_DONE',flush=True)
time.sleep(3600)
"#,
        file.to_str().unwrap()
    );
    let created=control.call("session.create",json!({"label":"rexling-kitty-test","initial_windows":[{"window_label":"Graphics","layout":{"type":"block","block":{"creator":"com.superlogical.terminal","flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/usr/bin/python3","-u","-c",script]}}}}]})).await.unwrap();
    let id = created["session_id"].as_str().unwrap();
    let block = created["initial_windows"][0]["block_ids"][0]
        .as_str()
        .unwrap();
    let result = async {
        control.attach(id).await?;
        let mut data = Data::open(&endpoint, &mut control, id, block, Trace::default()).await?;
        let mut replica = Replica::new(data.nonce);
        let live = tokio::time::timeout(Duration::from_secs(12), async {
            loop {
                replica.feed(&data.read().await?)?;
                if replica.ready() {
                    let frame = replica.terminal()?.frame()?;
                    if frame.text().contains("GRAPHICS_DONE") {
                        break Ok::<_, anyhow::Error>(frame);
                    }
                }
            }
        })
        .await??;
        let live_ids: Vec<_> = live.images.iter().map(|p| p.image.id).collect();
        println!("live image IDs: {live_ids:?}");
        anyhow::ensure!(
            live_ids.contains(&7),
            "inline image missing from live stream"
        );
        data.close().await?;
        let mut data = Data::open(&endpoint, &mut control, id, block, Trace::default()).await?;
        let mut restored = Replica::new(data.nonce);
        tokio::time::timeout(Duration::from_secs(12), async {
            while !restored.ready() {
                restored.feed(&data.read().await?)?;
            }
            Ok::<_, anyhow::Error>(())
        })
        .await??;
        let frame = restored.terminal()?.frame()?;
        let ids: Vec<_> = frame.images.iter().map(|p| p.image.id).collect();
        println!("restored image IDs: {ids:?}");
        anyhow::ensure!(ids.contains(&7), "inline image missing after reconnect");
        println!(
            "file medium: live={}, restored={}",
            live_ids.contains(&8),
            ids.contains(&8)
        );
        data.close().await?;
        Ok::<_, anyhow::Error>(())
    }
    .await;
    control
        .call("session.destroy", json!({"session_id":id}))
        .await
        .unwrap();
    control.close().await.unwrap();
    std::fs::remove_file(file).unwrap();
    result.unwrap();
}
