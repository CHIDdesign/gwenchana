"""시안 시제품을 그림을 끼워 한 파일로 만들고, 헤드리스 크롬으로 전체 비교 PNG를 찍는다.
사용: python -I -X utf8 build.py [폭 높이]"""
import base64, io, pathlib, subprocess, sys
from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CH = r'C:\Program Files\Google\Chrome\Application\chrome.exe'


def webp(im, q=82):
    b = io.BytesIO()
    im.save(b, 'WEBP', quality=q, method=6)
    return 'data:image/webp;base64,' + base64.b64encode(b.getvalue()).decode()


car = ROOT / 'app/src/main/assets/car'
top = Image.open(car / 'car_top.png')
top = top.crop(top.getbbox()).resize((400, round(400 * top.crop(top.getbbox()).height / top.crop(top.getbbox()).width)), Image.LANCZOS)
rear = Image.open(car / 'car_rear.png')
rear = rear.crop(rear.getbbox())
rear = rear.resize((1000, round(1000 * rear.height / rear.width)), Image.LANCZOS)
left = Image.open(ROOT / 'design/redesign/audit/00_car_before.png').convert('RGB').crop((0, 92, 854, 1300))

src = (HERE / 'proto.src.html').read_text(encoding='utf-8')
src = src.replace('{{CAR_TOP}}', webp(top)).replace('{{CAR_REAR}}', webp(rear)).replace('{{LEFT}}', webp(left, 72))
(HERE / 'proto.html').write_text(src, encoding='utf-8')
print('rear ratio', round(rear.height / rear.width, 3), 'top ratio', round(top.width / top.height, 3), 'size KB', len(src) // 1024)

page = HERE / '_page.html'
page.write_text('<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width">' + src, encoding='utf-8')
w, h = (sys.argv[1:] + ['1560', '2300'])[:2]
subprocess.run([CH, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1.5', '--virtual-time-budget=6000',
                f'--window-size={w},{h}', f'--screenshot={HERE / "sheet.png"}', page.as_uri()], check=True, capture_output=True, timeout=120)
page.unlink()
print('sheet ok')
