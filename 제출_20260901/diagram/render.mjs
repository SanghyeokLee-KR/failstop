// SVG 를 PNG 로 굽는다. 2배 배율은 슬라이드에서 확대해도 텍스트가 뭉개지지 않게 하기 위해서다.
//
// 종전 판은 다른 PC 의 web/node_modules 경로를 절대경로로 박아 그 PC 에서만 돌았다.
// 이 폴더가 sharp 를 자기 의존성으로 들고 있으므로 평범한 모듈 해석으로 찾는다.
// 준비: 이 폴더에서 npm install 을 한 번 돌린다.
//
// 사용: node render.mjs            -> 등록된 전 작업
//       node render.mjs arch_aws   -> 이름이 맞는 작업만

import sharp from 'sharp'
import { readFileSync, existsSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

// 굽는 대상. 도면이 늘면 여기에 행을 더한다.
// 크기는 적지 않는다. SVG 의 viewBox 에서 읽는다. 여기에 또 적으면 도면 높이를 바꿀 때
// 두 곳을 고쳐야 하고, 한쪽만 고치면 PNG 가 조용히 잘리거나 늘어난다.
const JOBS = [
  { name: 'arch_aws', src: 'arch_aws.svg', out: '시스템_아키텍처.png' },
  { name: 'arch_aws_slide', src: 'arch_aws_slide.svg', out: '시스템_아키텍처_슬라이드용.png' },
  { name: 'incident_flow', src: 'incident_flow.svg', out: '장애대응_흐름.png' },
  { name: 'incident_flow_slide', src: 'incident_flow_slide.svg', out: '장애대응_흐름_슬라이드용.png' },
  { name: 'layer_triage', src: 'layer_triage.svg', out: '계층판별_트리.png' },
  { name: 'layer_triage_slide', src: 'layer_triage_slide.svg', out: '계층판별_트리_슬라이드용.png' },
]

/** viewBox 에서 폭과 높이를 읽는다. 없으면 width/height 속성으로 물러선다. */
function svgSize(xml) {
  const vb = xml.match(/viewBox="\s*[\d.-]+\s+[\d.-]+\s+([\d.]+)\s+([\d.]+)/)
  if (vb) return { w: Math.round(+vb[1]), h: Math.round(+vb[2]) }
  const w = xml.match(/\swidth="(\d+)"/), h = xml.match(/\sheight="(\d+)"/)
  if (w && h) return { w: +w[1], h: +h[1] }
  throw new Error('SVG 크기를 읽지 못했다')
}

const filter = process.argv.slice(2)
const jobs = filter.length ? JOBS.filter((j) => filter.includes(j.name)) : JOBS

if (!jobs.length) {
  console.error(`대상 없음. 등록된 이름: ${JOBS.map((j) => j.name).join(', ')}`)
  process.exit(1)
}

let failed = 0
for (const { src, out } of jobs) {
  const srcPath = join(HERE, src)
  if (!existsSync(srcPath)) {
    // SVG 는 생성기가 만든다. 없는 것을 조용히 건너뛰면 낡은 PNG 가 제출본에 섞인다.
    console.error(`${src} 없음. 먼저 node arch_aws.mjs 를 돌린다`)
    failed++
    continue
  }
  const buf = readFileSync(srcPath)
  const { w, h } = svgSize(buf.toString('utf8'))
  // 한글 이름 SVG 도 여기서 같이 낸다. 종전에는 사람이 복사해 두고 생성기가 갱신하지
  // 않아 원본과 갈렸다(2026-09-09 실측: 사본이 폐기된 절 제목을 들고 있었다).
  writeFileSync(join(HERE, out.replace(/\.png$/, '.svg')), buf)
  const outPath = join(HERE, out)
  await sharp(buf, { density: 144 })
    .resize(w * 2, h * 2, { fit: 'fill' })
    .png({ compressionLevel: 9 })
    .toFile(outPath)
  const m = await sharp(outPath).metadata()
  console.log(`${out}  ${m.width}x${m.height}`)
}

process.exit(failed ? 1 : 0)
