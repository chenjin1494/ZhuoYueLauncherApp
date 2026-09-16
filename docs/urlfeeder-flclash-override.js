// 万能转发器 · FlClash 覆写脚本
// 与内置 VPN 共用拦截域名；重复执行不会添加重复规则。

const URLFEEDER_BLOCK_HOSTS = [
  "dev01.supagent.cn",
  "supagent.cn",
  "oaid.wocloud.cn",
  "sdk.api.oaid.wocloud.cn",
  "zy-pad-test-1253336831.cos.ap-guangzhou.myqcloud.com"
];

const main = (config) => {
  if (!config || typeof config !== "object") config = {};
  if (!Array.isArray(config.rules)) config.rules = [];
  const rules = config.rules;
  const existing = new Set(rules.map(rule => String(rule).toUpperCase().trim()));
  let insertAt = rules.findIndex(rule => {
    const value = String(rule).toUpperCase().trim();
    return value === "MATCH" || value.startsWith("MATCH,")
      || value === "FINAL" || value.startsWith("FINAL,");
  });
  if (insertAt < 0) insertAt = rules.length;
  for (const host of URLFEEDER_BLOCK_HOSTS) {
    const rule = `DOMAIN,${host},REJECT`;
    if (!existing.has(rule.toUpperCase())) {
      rules.splice(insertAt++, 0, rule);
      existing.add(rule.toUpperCase());
    }
  }
  console.log(`[万能转发器] 已加载 ${URLFEEDER_BLOCK_HOSTS.length} 条拦截规则`);
  return config;
};
