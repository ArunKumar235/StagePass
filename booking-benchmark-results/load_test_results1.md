PS E:\StagePass> docker compose -f k6/docker-compose.yml up
Attaching to k6-benchmark
k6-benchmark  | 
k6-benchmark  |          /\      Grafana   /‾‾/
k6-benchmark  |     /\  /  \     |\  __   /  /   
k6-benchmark  |    /  \/    \    | |/ /  /   ‾‾\
k6-benchmark  |   /          \   |   (  |  (‾)  |
k6-benchmark  |  / __________ \  |_|\_\  \_____/
k6-benchmark  |         output: -
k6-benchmark  |
k6-benchmark  |      scenarios: (100.00%) 1 scenario, 500 max VUs, 5m30s max duration (incl. graceful stop):
k6-benchmark  |               * booking_rush: 1 iterations for each of 500 VUs (maxDuration: 5m0s, gracefulStop: 30s)
k6-benchmark  |
k6-benchmark  | time="2026-07-21T18:43:37Z" level=info msg="🚀 Setting up 500:1 concurrency benchmark environment..." source=console
k6-benchmark  | time="2026-07-21T18:43:37Z" level=info msg="✅ Venue created: StagePass k6 Arena 1784659417627 (ID: d9861356-61c3-45e0-8809-bebe292d6a8b)" source=console
k6-benchmark  | time="2026-07-21T18:43:37Z" level=info msg="✅ Event created: k6 Concurrency Clash 1784659417627 (ID: 28902b03-5057-4f50-93ae-3f4519def80c)" source=console
k6-benchmark  | time="2026-07-21T18:43:37Z" level=info msg="✅ Event published successfully." source=console
k6-benchmark  | time="2026-07-21T18:43:37Z" level=info msg="✅ Retrieved 1 available seat(s) from the event seat map." source=console
k6-benchmark  | time="2026-07-21T18:43:37Z" level=info msg="👥 Registering and authenticating 500 customer accounts. This might take a minute..." source=console
k6-benchmark  |
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]          
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]          
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]         
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |                                   
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]    
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |                                   
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]   
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]       
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]        
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]       
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]          
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]      
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]          
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]        
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |                                   
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]       
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |                                    
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |                                   
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]         
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]         
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |                                  
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]    
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  |                              
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]         
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]         
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]  
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]
k6-benchmark  | 
k6-benchmark  | Run            [ 100% ] setup()
k6-benchmark  | booking_rush   [   0% ]                                              
k6-benchmark  | time="2026-07-21T18:46:29Z" level=info msg="✅ Successfully authenticated 500/500 simulated customers." source=console
k6-benchmark  | 
k6-benchmark  | running (2m52.8s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m01.0s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (2m53.8s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m02.0s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (2m54.8s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m03.0s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (2m55.8s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m04.0s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (2m57.0s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m05.2s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (2m57.8s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m06.0s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (2m58.8s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m07.0s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (2m59.8s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m08.0s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m00.8s), 500/500 VUs, 0 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   0% ] 500 VUs  0m09.0s/5m0s  000/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m01.8s), 482/500 VUs, 18 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [   4% ] 500 VUs  0m10.0s/5m0s  018/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m02.8s), 436/500 VUs, 64 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  13% ] 500 VUs  0m11.0s/5m0s  064/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m03.8s), 416/500 VUs, 84 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  17% ] 500 VUs  0m12.0s/5m0s  084/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m04.8s), 364/500 VUs, 136 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  27% ] 500 VUs  0m13.0s/5m0s  136/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m05.8s), 328/500 VUs, 172 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  34% ] 500 VUs  0m14.0s/5m0s  172/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m06.8s), 311/500 VUs, 189 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  38% ] 500 VUs  0m15.0s/5m0s  189/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m07.8s), 251/500 VUs, 249 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  50% ] 500 VUs  0m16.0s/5m0s  249/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m08.8s), 161/500 VUs, 339 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  68% ] 500 VUs  0m17.0s/5m0s  339/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m09.8s), 071/500 VUs, 429 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  86% ] 500 VUs  0m18.0s/5m0s  429/500 iters, 1 per VU
k6-benchmark  | 
k6-benchmark  | running (3m10.8s), 003/500 VUs, 497 complete and 0 interrupted iterations
k6-benchmark  | booking_rush   [  99% ] 500 VUs  0m19.0s/5m0s  497/500 iters, 1 per VU
k6-benchmark  | time="2026-07-21T18:46:48Z" level=info msg="🏁 Benchmark execution finished successfully." source=console
k6-benchmark  | 
k6-benchmark  | 
k6-benchmark  |   █ THRESHOLDS 
k6-benchmark  | 
k6-benchmark  |     checks
k6-benchmark  |     ✓ 'rate>0.99' rate=100.00%
k6-benchmark  | 
k6-benchmark  | 
k6-benchmark  |   █ TOTAL RESULTS 
k6-benchmark  | 
k6-benchmark  |     checks_total.......: 504     2.639735/s
k6-benchmark  |     checks_succeeded...: 100.00% 504 out of 504
k6-benchmark  |     checks_failed......: 0.00%   0 out of 504
k6-benchmark  | 
k6-benchmark  |     ✓ Venue created successfully
k6-benchmark  |     ✓ Event created successfully
k6-benchmark  |     ✓ Event published successfully
k6-benchmark  |     ✓ Seats retrieved successfully
k6-benchmark  |     ✓ Seat lock rejected gracefully
k6-benchmark  |     ✓ Booking successful (202)
k6-benchmark  | 
k6-benchmark  |     CUSTOM
k6-benchmark  |     seat_lock_failures.............: 499    2.613547/s                                     
k6-benchmark  |     successful_bookings............: 1      0.005238/s
k6-benchmark  | 
k6-benchmark  |     HTTP
k6-benchmark  |     http_req_duration..............: avg=3.37s    min=5.12ms  med=310.59ms max=13.76s p(90)=11.75s  p(95)=12.59s  
k6-benchmark  |       { expected_response:true }...: avg=347.98ms min=21.14ms med=310.43ms max=9.9s   p(90)=397.8ms p(95)=463.13ms
k6-benchmark  |     http_req_failed................: 66.42% 999 out of 1504
k6-benchmark  |     http_reqs......................: 1504   7.877304/s
k6-benchmark  | 
k6-benchmark  |     EXECUTION
k6-benchmark  |     iteration_duration.............: avg=14.97s   min=9.07s   med=15.88s   max=18.99s p(90)=18.04s  p(95)=18.52s  
k6-benchmark  |     iterations.....................: 500    2.618785/s
k6-benchmark  |     vus............................: 1      min=0           max=500
k6-benchmark  |     vus_max........................: 500    min=500         max=500
k6-benchmark  | 
k6-benchmark  |     NETWORK
k6-benchmark  |     data_received..................: 1.3 MB 6.8 kB/s
k6-benchmark  |     data_sent......................: 531 kB 2.8 kB/s
k6-benchmark  | 
k6-benchmark  | 
k6-benchmark  | 
k6-benchmark  | 
k6-benchmark  | running (3m10.9s), 000/500 VUs, 500 complete and 0 interrupted iterations
k6-benchmark  | booking_rush ✓ [ 100% ] 500 VUs  0m19.1s/5m0s  500/500 iters, 1 per VU
k6-benchmark exited with code 0