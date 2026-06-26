const fs = require('fs');
let f = 'C:/Users/Arthomas/.openclaw/workspace/projects/Pai_Android1/app/src/main/java/com/pai/android/agent/DecisionEngine.kt';
let c = fs.readFileSync(f, 'utf8');
c = c.replace(
  'com.pai.android.data.repository.buildNativeToolDefs(skillRegistry)',
  'buildNativeToolDefs(setOf("web_search", "contacts_search", "sms_send", "weather_forecast", "file_system_search", "web_fetch"))'
);
fs.writeFileSync(f, c, 'utf8');
console.log('OK');
