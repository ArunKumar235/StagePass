/* 
   StagePass Payment Authorization Scripts
   Theme: Galactic Glassmorphism & Cyber Orchid (Consistent with User Service)
*/

function makePayment() {
    const amountInput = document.getElementById('amount').value;
    if (!amountInput || amountInput <= 0) {
        alert("Please enter a valid amount.");
        return;
    }

    // Convert to paise (e.g. ₹250.00 -> 25000 paise)
    const amountInPaise = Math.round(parseFloat(amountInput) * 100);

    // Standard checkout options using the StagePass Razorpay test key.
    // We omit order_id so that Razorpay creates a standalone authorization directly on the client side.
    const options = {
        "key": "rzp_test_Su03YOIVUkTfIc",
        "amount": amountInPaise,
        "currency": "INR",
        "name": "StagePass Development",
        "description": "Test Token Authorization",
        "image": "https://img.icons8.com/color/96/000000/movie-ticket.png",
        "prefill": {
            "name": "StagePass Tester",
            "email": "tester@stagepass.com",
            "contact": "9999999999"
        },
        "theme": {
            "color": "#9d4edd"
        },
        "handler": function (response) {
            // Populate the values in the UI
            document.getElementById('tokenValue').innerText = response.razorpay_payment_id;
            document.getElementById('sigValue').innerText = response.razorpay_signature || "N/A (Standalone)";

            // Reveal the panel with an elegant slide down
            const panel = document.getElementById('resultPanel');
            panel.style.display = 'block';
            panel.scrollIntoView({ behavior: 'smooth' });
        },
        "modal": {
            "ondismiss": function () {
                console.log('Payment modal closed by user');
            }
        }
    };

    const rzp = new Razorpay(options);

    // Handle opening error
    rzp.on('payment.failed', function (response) {
        alert("Authorization failed: " + response.error.description);
    });

    rzp.open();
}

function copyToken(elementId, btn) {
    const val = document.getElementById(elementId).innerText;
    navigator.clipboard.writeText(val).then(() => {
        const originalText = btn.innerHTML;
        btn.innerHTML = "<span>Copied!</span>";
        btn.classList.add('copied');

        setTimeout(() => {
            btn.innerHTML = originalText;
            btn.classList.remove('copied');
        }, 2000);
    }).catch(err => {
        console.error("Could not copy text: ", err);
    });
}
