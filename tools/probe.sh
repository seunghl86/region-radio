#!/bin/bash
# 방송사 편성표·재생 주소가 응답하는지 확인하는 점검용 스크립트
D=$(TZ=Asia/Seoul date +%Y%m%d); Y=${D:0:4}; M=${D:4:2}; DD=${D:6:2}
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
p(){ echo "=== $1"; curl -sS -m 20 -A "$UA" ${3:+-e "$3"} -w '\n[http %{http_code}]\n' "$2" | head -c ${4:-1500}; echo; }
p KBS-SCHED "https://static.api.kbs.co.kr/mediafactory/v1/schedule/weekly?rtype=json&local_station_code=00&channel_code=21&program_planned_date_from=$D&program_planned_date_to=$D" "https://schedule.kbs.co.kr/"
p KBS-SCHED-25 "https://static.api.kbs.co.kr/mediafactory/v1/schedule/weekly?rtype=json&local_station_code=00&channel_code=25&program_planned_date_from=$D&program_planned_date_to=$D" "https://schedule.kbs.co.kr/" 600
p MBC-SCHED "https://miniunit.imbc.com/Schedule?rtype=json" "http://mini.imbc.com/"
p MBC-SCHED2 "https://miniunit.imbc.com/Schedule/schedulelist?rtype=json" "http://mini.imbc.com/" 800
p SBS-SCHED-POWER "https://static.cloud.sbs.co.kr/schedule/$Y/$((10#$M))/$((10#$DD))/Power.json" "https://www.sbs.co.kr/"
p SBS-SCHED-LOVE "https://static.cloud.sbs.co.kr/schedule/$Y/$((10#$M))/$((10#$DD))/Love.json" "https://www.sbs.co.kr/" 600
p SBS-SCHED-POWER-PAD "https://static.cloud.sbs.co.kr/schedule/$Y/$M/$DD/Power.json" "https://www.sbs.co.kr/" 600
p KBS-STREAM "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25" "https://onair.kbs.co.kr/" 900
p MBC-STREAM "https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=mfm" "http://mini.imbc.com/" 400
p SBS-STREAM "https://apis.sbs.co.kr/play-api/1.0/livestream/powerpc/powerfm?protocol=hls&ssl=Y" "https://www.sbs.co.kr/" 400
p TBS-STREAM "https://cdnfm.tbs.seoul.kr/tbs/_definst_/tbs_fm_web_360.smil/playlist.m3u8" "" 300
p EBS-STREAM "https://ebsonair.ebs.co.kr/fmradiofamilypc/familypc1m/playlist.m3u8" "" 300
