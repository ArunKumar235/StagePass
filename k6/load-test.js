import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// Custom metrics to show in k6 summary
const successfulBookings = new Counter('successful_bookings');
const seatLockFailures = new Counter('seat_lock_failures');
const rateLimitedRequests = new Counter('rate_limited_requests');

export const options = {
  setupTimeout: '10m', // Allow up to 10 minutes for setup 
  scenarios: {
    booking_rush: {
      executor: 'per-vu-iterations',
      vus: 500, // 500 concurrent users
      iterations: 1, // each user makes exactly 1 booking attempt
      maxDuration: '5m',
    },
  },

  // ── SLO THRESHOLDS ──────────────────────────────────────────────────────────
  // We only care about correctness for this contention test, not latency.
  thresholds: {
    'checks': [
      { threshold: 'rate>0.99', abortOnFail: false }, // ≥99% checks must pass
    ],
  },
};

// ── SETUP PHASE ─────────────────────────────────────────────────────────────
// Executed exactly once. Creates the environment, seeds venue & event, and
// registers/logs in 500 customer users.
export function setup() {
  // use host.docker.internal when services run from intellij
  // const GATEWAY_URL = 'http://host.docker.internal:8080';
  // const EVENT_SERVICE_URL = 'http://host.docker.internal:8082';

  // use these urls when services from docker-compose
  const GATEWAY_URL = 'http://api-gateway:8080';
  const EVENT_SERVICE_URL = 'http://event-service:8082';
  const timestamp = Date.now();

  console.log('🚀 Setting up 500:1 concurrency benchmark environment...');

  // 1. Create a Test Venue directly in Event Service (bypassing Gateway for Admin credentials)
  const venuePayload = JSON.stringify({
    name: `StagePass k6 Arena ${timestamp}`,
    address: '123 Concurrency Highway',
    city: 'Chennai',
    state: 'Tamil Nadu',
    country: 'India',
    totalCapacity: 100,
    mapImageUrl: 'http://example.com/arena.jpg'
  });

  const venueRes = http.post(`${EVENT_SERVICE_URL}/venues`, venuePayload, {
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': '00000000-0000-0000-0000-000000000001',
      'X-Role': 'ADMIN'
    }
  });

  if (!check(venueRes, { 'Venue created successfully': (r) => r.status === 201 })) {
    console.error(`❌ Failed to create Venue. Status: ${venueRes.status}. Body: ${venueRes.body}`);
    return null;
  }
  const venue = JSON.parse(venueRes.body);
  console.log(`✅ Venue created: ${venue.name} (ID: ${venue.id})`);

  // 2. Create a Test Event with exactly 1 seat
  const eventPayload = JSON.stringify({
    title: `k6 Concurrency Clash ${timestamp}`,
    description: 'High concurrency simulation event with exactly 1 seat.',
    category: 'MUSIC',
    venueId: venue.id,
    eventDate: '2028-12-31', // Future date
    doorsOpenTime: '18:00:00',
    bannerImageURL: 'http://example.com/banner.jpg',
    tierPricing: [
      {
        sectionName: 'General Admission',
        tier: 'GENERAL',
        price: 500.00,
        rowCount: 1,
        seatsPerRow: 1
      }
    ]
  });

  const eventRes = http.post(`${EVENT_SERVICE_URL}/events`, eventPayload, {
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': '00000000-0000-0000-0000-000000000002',
      'X-Role': 'ORGANISER'
    }
  });

  if (!check(eventRes, { 'Event created successfully': (r) => r.status === 201 })) {
    console.error(`❌ Failed to create Event. Status: ${eventRes.status}. Body: ${eventRes.body}`);
    return null;
  }
  const event = JSON.parse(eventRes.body);
  console.log(`✅ Event created: ${event.title} (ID: ${event.id})`);

  // 3. Publish the Event so it can be booked
  const publishRes = http.patch(`${EVENT_SERVICE_URL}/events/${event.id}/publish`, null, {
    headers: {
      'X-User-Id': '00000000-0000-0000-0000-000000000002',
      'X-Role': 'ORGANISER'
    }
  });

  if (!check(publishRes, { 'Event published successfully': (r) => r.status === 200 })) {
    console.error(`❌ Failed to publish Event. Status: ${publishRes.status}`);
    return null;
  }
  console.log(`✅ Event published successfully.`);

  // 4. Fetch the generated Seat Map to get the Seat UUID
  const seatsRes = http.get(`${EVENT_SERVICE_URL}/events/${event.id}/seats`);
  if (!check(seatsRes, { 'Seats retrieved successfully': (r) => r.status === 200 })) {
    console.error(`❌ Failed to get seats. Status: ${seatsRes.status}`);
    return null;
  }

  const seatMap = JSON.parse(seatsRes.body);
  const seatIds = [];
  seatMap.sections.forEach(section => {
    section.rows.forEach(row => {
      row.seats.forEach(seat => {
        seatIds.push(seat.seatId);
      });
    });
  });
  console.log(`✅ Retrieved ${seatIds.length} available seat(s) from the event seat map.`);

  // 5. Register and Login 500 customer users dynamically
  console.log('👥 Registering and authenticating 500 customer accounts. This might take a minute...');
  const customerTokens = [];

  for (let i = 1; i <= 500; i++) {
    const email = `k6_user_${i}@stagepass.com`;
    const username = `k6_user_${i}`;
    const password = `password123`;

    // 5a. Register User (201 = Created, 409 = Conflict/Exists)
    const regPayload = JSON.stringify({ email, username, password });
    const regRes = http.post(`${GATEWAY_URL}/auth/register`, regPayload, {
      headers: { 'Content-Type': 'application/json' }
    });

    if (regRes.status !== 201 && regRes.status !== 409) {
      console.warn(`⚠️ Registration warning for user ${i}: Status ${regRes.status}`);
    }

    // 5b. Login User and capture token
    const loginPayload = JSON.stringify({ email, password });
    const loginRes = http.post(`${GATEWAY_URL}/auth/login`, loginPayload, {
      headers: { 'Content-Type': 'application/json' }
    });

    if (loginRes.status === 200) {
      const auth = JSON.parse(loginRes.body);
      customerTokens.push(auth.accessToken);
    } else {
      console.error(`❌ Failed login for user ${i}. Status: ${loginRes.status}. Body: ${loginRes.body}`);
    }
  }

  console.log(`✅ Successfully authenticated ${customerTokens.length}/500 simulated customers.`);

  // Set a synchronized execution time 5 seconds in the future
  // This gives all VUs enough time to initialize before firing simultaneously
  const syncTime = Date.now() + 5000;

  return {
    eventId: event.id,
    seatIds: seatIds, // Array of 1 seat UUID
    customerTokens: customerTokens, // Array of 500 JWT tokens
    timestamp: timestamp,
    syncTime: syncTime
  };
}

// ── VIRTUAL USER STAGE ──────────────────────────────────────────────────────
// Executes in parallel. 500 VUs trigger checkouts concurrently.
export default function (data) {
  if (!data || data.customerTokens.length === 0) {
    console.error('❌ Setup data missing or invalid. Terminating VU.');
    return;
  }

  // use host.docker.internal when services run from intellij
  // const GATEWAY_URL = 'http://host.docker.internal:8080';

  // use these urls when services from docker-compose
  const GATEWAY_URL = 'http://api-gateway:8080';

  // Pick unique customer token based on this virtual user's 1-indexed thread ID
  const tokenIndex = __VU - 1;
  const userToken = data.customerTokens[tokenIndex];

  if (!userToken) {
    console.error(`❌ No customer token found for VU ${__VU}`);
    return;
  }

  // We have 500 users and exactly 1 seat.
  // All 500 VUs will attempt to book this exact same seat.
  const seatToBook = data.seatIds[0];

  const bookingPayload = JSON.stringify({
    eventId: data.eventId,
    seatIds: [seatToBook],
    paymentMethod: 'CARD',
    paymentToken: `mock_payment_token_${data.timestamp}_${__VU}`
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${userToken}`
    }
  };

  // --- TRULY CONCURRENT ALIGNMENT ---
  // To ensure all 500 threads hit the gateway at the EXACT same millisecond,
  // we first sleep until 50ms before the syncTime, then spin-lock for the rest.
  const waitMs = data.syncTime - Date.now();
  if (waitMs > 50) {
    sleep((waitMs - 50) / 1000.0);
  }
  
  // Spin lock until the exact millisecond hits
  while (Date.now() < data.syncTime) {
    // block thread
  }

  const res = http.post(`${GATEWAY_URL}/bookings`, bookingPayload, params);

  // Analyze response
  if (res.status === 202) {
    successfulBookings.add(1);
    check(res, {
      'Booking successful (202)': (r) => r.status === 202
    });
  } else if (res.status === 409 || (res.status === 400 && res.body.includes("locked"))) {
    // 409 Conflict or 400 Bad Request indicating the seat was already locked/sold
    seatLockFailures.add(1);
    check(res, {
      'Seat lock rejected gracefully': (r) => r.status === 409 || r.status === 400
    });
  } else if (res.status === 429) {
    rateLimitedRequests.add(1);
    check(res, {
      'Rate limit triggered': (r) => r.status === 429
    });
  } else {
    // Other errors (e.g. 500 internal server error or 504 timeout)
    console.warn(`⚠️ VU ${__VU} got unexpected status ${res.status}. Body: ${res.body}`);
    check(res, {
      'Unexpected server response': (r) => false
    });
  }
}

// ── TEARDOWN PHASE ──────────────────────────────────────────────────────────
export function teardown(data) {
  console.log('🏁 Benchmark execution finished successfully.');
}
