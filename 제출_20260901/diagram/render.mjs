// SVG 를 PNG 로 굽는다. 2배 배율은 슬라이드에서 확대해도 텍스트가 뭉개지지 않게 하기 위해서다.
import { pathToFileURL } from 'node:url'
import { readFileSync } from 'node:fs'
const sharp = (await import(pathToFileURL('C:/Users/DEV-PC/Desktop/프로젝트/web/node_modules/sharp/lib/index.js').href)).default
const jobs = [
  ['arch_aws.svg', '시스템_아키텍처.png', 2100, 1000],
  ['arch_aws_slide.svg', '시스템_아키텍처_슬라이드용.png', 2100, 938],
]
for (const [src, out, w, h] of jobs) {
  await sharp(readFileSync(src), { density: 144 }).resize(w * 2, h * 2, { fit: 'fill' }).png({ compressionLevel: 9 }).toFile(out)
  const m = await sharp(out).metadata()
  console.log(`${out}  ${m.width}x${m.height}`)
}
