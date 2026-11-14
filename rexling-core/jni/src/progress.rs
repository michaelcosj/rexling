//! OSC 9;4 is a terminal effect reported by the canonical Rex server.
use serde_json::{Value,json};
use std::collections::BTreeMap;

pub fn update(reports: &mut BTreeMap<String,Value>,session: &str,block: &str,event: &Value)->bool {
    let key=super::activity::pane_key(session,block);
    let Some(state)=event["state"].as_str().filter(|s|matches!(*s,"set"|"indeterminate"|"pause"|"error"|"remove")) else{return false;};
    if state=="remove" {return reports.remove(&key).is_some();}
    let percent=if state=="indeterminate" {None} else {event["progress"].as_u64().filter(|p|*p<=100).or_else(||
        matches!(state,"pause"|"error").then(||reports.get(&key).and_then(|p|p["progress"].as_u64())).flatten())};
    let value=json!({"session":session,"block":block,"state":state,"progress":percent});
    if reports.get(&key)==Some(&value) {return false;}
    if reports.len()>=1024 && !reports.contains_key(&key) {return false;}
    reports.insert(key,value);true
}
pub fn selected(reports: &BTreeMap<String,Value>,session: &str)->Value {
    Value::Object(reports.values().filter(|v|v["session"]==session).filter_map(|v|
        Some((v["block"].as_str()?.into(),json!({"state":v["state"],"progress":v["progress"]})))).collect())
}
pub fn activity(base: &Value,panes: &Value,reports: &BTreeMap<String,Value>)->Value {
    let mut items=base.as_array().cloned().unwrap_or_default();
    for report in reports.values() {
        let progress=json!({"state":report["state"],"progress":report["progress"]});
        if let Some(root)=items.iter_mut().find(|v|v["session"]==report["session"] && v["block"]==report["block"] && v["id"].as_str().is_none_or(str::is_empty) && matches!(v["state"].as_str(),Some("working"|"blocked"))) {
            if !root["progress"].is_null() {continue;}
            root["terminalProgress"]=progress.clone();
            if root["state"]=="working" && root["progress"].is_null() {root["progress"]=report["progress"].clone();}
            continue;
        }
        let Some(pane)=panes.as_array().into_iter().flatten().find(|p|p["session"]["session_id"]==report["session"] && p["block"]["block_id"]==report["block"]) else{continue;};
        items.push(json!({"session":report["session"],"block":report["block"],"sessionLabel":pane["session"]["label"],
            "window":pane["window"]["window_id"],"windowLabel":pane["window"]["label"],"blockLabel":pane["block"]["label"],
            "id":"osc9-progress","title":pane["block"]["label"],"app":"","owner":"","kind":"progress","progressOnly":true,
            "state":match report["state"].as_str() {Some("error")=>"error",Some("pause")=>"idle",_=>"working"},
            "progress":report["progress"],"terminalProgress":progress,"message":"","updatedAt":""}));
    }
    Value::Array(items)
}
#[cfg(test)] mod tests {
    use super::*;
    #[test] fn reports_validate_coalesce_retain_paused_percent_and_clear() {
        let mut reports=BTreeMap::new();
        assert!(update(&mut reports,"s","b",&json!({"state":"set","progress":35})));
        assert!(!update(&mut reports,"s","b",&json!({"state":"set","progress":35})));
        update(&mut reports,"s","b",&json!({"state":"pause"}));assert_eq!(selected(&reports,"s")["b"]["progress"],35);
        assert!(!update(&mut reports,"s","b",&json!({"state":"unknown"})));
        update(&mut reports,"s","b",&json!({"state":"indeterminate","progress":255}));assert!(selected(&reports,"s")["b"]["progress"].is_null());
        assert_eq!(selected(&reports,"other"),json!({}));
        assert!(update(&mut reports,"s","b",&json!({"state":"remove"})));assert!(reports.is_empty());
    }
    #[test] fn progress_enriches_root_activity_without_overwriting_attention_or_creating_completion() {
        let mut reports=BTreeMap::new();update(&mut reports,"s","b",&json!({"state":"set","progress":50}));
        let base=json!([{"session":"s","block":"b","id":"","state":"blocked","progress":10}]);
        let merged=activity(&base,&json!([]),&reports);assert_eq!(merged[0]["state"],"blocked");assert_eq!(merged[0]["progress"],10);assert!(merged[0]["terminalProgress"].is_null());
        let panes=json!([{"session":{"session_id":"s","label":"Session"},"window":{"window_id":"w","label":"Tab"},"block":{"block_id":"b","label":"cargo"}}]);
        let synthetic=activity(&json!([]),&panes,&reports);assert_eq!(synthetic[0]["state"],"working");assert_eq!(synthetic[0]["progressOnly"],true);
        update(&mut reports,"s","b",&json!({"state":"remove"}));assert_eq!(activity(&json!([]),&panes,&reports),json!([]));
    }
}
