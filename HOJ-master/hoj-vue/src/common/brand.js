export const BRAND = Object.freeze({
  name: '成理工程在线测评平台',
  shortName: 'CDUTETC',
  projectName: 'CDUTETC',
  projectUrl: 'https://github.com/heqiuyu209/heqiuyu-oj',
  description: '面向算法学习、程序设计与 XCPC 集训的在线测评平台。',
});

// Branding is owned here; operational switches still come from the HOJ API.
export function withBrand(config = {}) {
  return { ...config, ...BRAND };
}
