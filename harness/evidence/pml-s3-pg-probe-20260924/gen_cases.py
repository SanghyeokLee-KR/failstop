"""예약액 양자화 대조용 입력을 만든다(ADR 0034 S3 설계 3절 P1).

지정가 매수 예약은 price x qty 를 quote 자산 scale 로 올림(Java setScale(s, RoundingMode.UP))한 값이다. DB 가 같은 값을
내는지 보려고 경계 사례와 난수 사례를 한 파일로 만든다. 난수 시드는 고정이다(engineering.md 랜덤 규칙).
출력: cases.csv (id,price,qty,scale). 금액은 모두 문자열이고 부동소수점을 거치지 않는다.
사용: python gen_cases.py > cases.csv
"""
import random

EDGE = [
    ("50000", "1", 0),
    ("50000.00", "1.000", 0),
    ("0.00000001", "0.00000001", 0),
    ("0.00000001", "0.00000001", 8),
    ("0.00000001", "0.00000001", 18),
    ("0.000000000000000001", "0.000000000000000001", 18),
    ("0.000000000000000001", "1", 18),
    ("123.456789012345678", "0.000000000000000009", 18),
    ("99999999999999999999.999999999999999999", "1", 18),
    ("9999999999.999999999999999999", "99999999999.999999999999999999", 18),
    ("1000", "0.0001", 0),
    ("1001", "0.0001", 0),
    ("1", "0.1", 0),
    ("100.000", "2.500", 2),
    ("33.33", "3", 2),
    ("33.333", "3", 2),
    ("0.5", "0.5", 0),
    ("7", "0.142857142857142857", 18),
    ("7", "0.142857142857142857", 8),
    ("12345678901234567890", "0.00000001", 0),
]


def rand_dec(rng, int_digits, frac_digits):
    ip = str(rng.randrange(0, 10 ** int_digits)) if int_digits > 0 else "0"
    if frac_digits == 0:
        s = ip
    else:
        fp = "".join(str(rng.randrange(10)) for _ in range(frac_digits))
        s = ip + "." + fp
    if s.strip("0.") == "":
        s = "1"
    return s


def main():
    rng = random.Random(20260924)
    print("id,price,qty,scale")
    n = 0
    for price, qty, scale in EDGE:
        n += 1
        print(f"{n},{price},{qty},{scale}")
    for _ in range(3000):
        n += 1
        price = rand_dec(rng, rng.randrange(0, 11), rng.randrange(0, 19))
        qty = rand_dec(rng, rng.randrange(0, 11), rng.randrange(0, 19))
        scale = rng.choice([0, 0, 2, 4, 8, 8, 18, 18])
        print(f"{n},{price},{qty},{scale}")


if __name__ == "__main__":
    main()
