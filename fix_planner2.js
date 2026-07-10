const fs = require('fs');
const path = 'C:/Users/Arthomas/.openclaw/workspace/projects/Pai_Android1/app/src/main/java/com/pai/android/agent/AgentPlanner.kt';
let content = fs.readFileSync(path, 'utf8');

// Find the sensorPatterns section
const marker = 'val sensorPatterns';
const idx = content.indexOf(marker);
if (idx < 0) {
    console.log('sensorPatterns not found');
    process.exit(1);
}

// Find the end of this block: the closing } before the weatherPatterns section
const weatherMarker = 'val weatherPatterns';
const weatherIdx = content.indexOf(weatherMarker, idx);
if (weatherIdx < 0) {
    console.log('weatherPatterns not found');
    process.exit(1);
}

console.log('Found sensorPatterns block from', idx, 'to', weatherIdx);

// Build replacement
const replacement = 
'        // Явные запросы про датчики телефона\n' +
'        val explicitSensor = listOf(\u0022датчик\u0022, \u0022sensor\u0022, \u0022сенсор\u0022, \u0022освещеннос\u0022, \u0022люкс\u0022, \u0022давлени\u0022, \u0022барометр\u0022)\n' +
'        if (explicitSensor.any { lower.contains(it) }) {\n' +
'            return Pair(\u0022device_sensors\u0022, mapOf(\u0022action\u0022 to \u0022all\u0022, \u0022query\u0022 to query))\n' +
'        }\n' +
'        // Запросы про температуру/батарею телефона (отличаем от погоды)\n' +
'        if (lower.contains(\u0022температур\u0022) && (lower.contains(\u0022телефон\u0022) || lower.contains(\u0022батаре\u0022) || lower.contains(\u0022устройств\u0022) || lower.contains(\u0022заряд\u0022) || lower.contains(\u0022phone\u0022) || lower.contains(\u0022battery\u0022))) {\n' +
'            return Pair(\u0022device_sensors\u0022, mapOf(\u0022action\u0022 to \u0022battery_temp\u0022, \u0022query\u0022 to query))\n' +
'        }\n';

// Replace the old block
const oldBlockEnd = weatherIdx - 1; // go to last char before weatherPatterns
const oldBlock = content.substring(idx, weatherIdx);

content = content.substring(0, idx) + replacement + content.substring(weatherIdx);

fs.writeFileSync(path, content, 'utf8');
console.log('Done. Replaced', oldBlock.length, 'chars with', replacement.length, 'chars');
