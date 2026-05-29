package org.stagepass.bookingservice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateBookingRequest(

        @NotNull
        UUID eventId,

        @NotEmpty
        @Size(max=10)
        List<UUID> seatIds,

        @NotNull
        String paymentMethod,

        String paymentToken // paymentToken is required for card payments, optional for UPI/Netbanking (handled by Razorpay's checkout flow)
        // For card payments, the frontend collects card details and calls Razorpay's tokenization API to get a paymentToken (a secure reference to the card info).
        // This token is sent to Booking Service, which forwards it to Payment Service. Razorpay processes the payment using the token without Booking Service ever handling raw card data — ensuring PCI compliance.
        // For UPI/Netbanking, the frontend can directly call Razorpay's checkout flow, which handles the entire payment process (including user authentication) and returns a paymentToken upon successful payment. In this case, the frontend sends the paymentToken to Booking Service after payment completion.

) {
}
