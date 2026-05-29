package org.stagepass.notificationservice.service;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.stagepass.notificationservice.dto.BookingConfirmedEvent;
import org.stagepass.notificationservice.dto.SeatDetail;
import org.stagepass.notificationservice.exception.NotificationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;

@Slf4j
@Service
public class TicketPdfService {

        // StagePass Cyber Orchid & Galactic theme colors for high-contrast printable
        // PDF
        private static final DeviceRgb BRAND_COLOR = new DeviceRgb(157, 78, 221); // #9d4edd (Cyber Orchid Purple)
        private static final DeviceRgb BRAND_DARK_COLOR = new DeviceRgb(123, 44, 191); // #7b2cbf (Deep Purple)
        private static final DeviceRgb BRAND_CYAN = new DeviceRgb(0, 180, 160); // Clean green-cyan for high-contrast
                                                                                // print
        private static final DeviceRgb BG_TICKET_CARD = new DeviceRgb(249, 247, 252); // Soft light purple-tint
                                                                                      // background
        private static final DeviceRgb LIGHT_BORDER = new DeviceRgb(230, 222, 240); // Soft violet-gray border
        private static final DeviceRgb TEXT_DARK = new DeviceRgb(21, 20, 33); // #151421 (Deep slate dark text)
        private static final DeviceRgb TEXT_MUTED = new DeviceRgb(115, 110, 135); // Cool grey muted text

        /**
         * Generates an in-memory PDF ticket for the given booking.
         * Never writes to disk — returns raw bytes for attachment.
         *
         * @param event the fully parsed booking-confirmed Kafka payload
         * @return PDF bytes to attach to the booking confirmation email
         */
        public byte[] generateTicket(BookingConfirmedEvent event) {
                try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        PdfWriter writer = new PdfWriter(baos);
                        PdfDocument pdf = new PdfDocument(writer);
                        Document doc = new Document(pdf, PageSize.A5);
                        doc.setMargins(20, 20, 20, 20);

                        PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
                        PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);

                        // ── Header ──────────────────────────────────────────────
                        Paragraph header = new Paragraph()
                                        .add(new Text("Stage").setFont(bold).setFontColor(BRAND_COLOR))
                                        .add(new Text("Pass").setFont(bold).setFontColor(BRAND_CYAN))
                                        .setFontSize(22)
                                        .setTextAlignment(TextAlignment.CENTER);
                        doc.add(header);

                        Paragraph eventTitle = new Paragraph(event.eventName())
                                        .setFont(bold)
                                        .setFontSize(16)
                                        .setFontColor(TEXT_DARK)
                                        .setTextAlignment(TextAlignment.CENTER)
                                        .setMarginTop(4);
                        doc.add(eventTitle);

                        Paragraph subtitle = new Paragraph("YOUR SECURE ENTRY TICKET")
                                        .setFont(regular)
                                        .setFontSize(8)
                                        .setFontColor(TEXT_MUTED)
                                        .setTextAlignment(TextAlignment.CENTER)
                                        .setMarginBottom(6);
                        doc.add(subtitle);

                        // Divider line
                        doc.add(new Paragraph("─────────────────────────────────────────")
                                        .setFontSize(8).setFontColor(LIGHT_BORDER)
                                        .setTextAlignment(TextAlignment.CENTER));

                        // ── Event Details ────────────────────────────────────────
                        doc.add(sectionHeading("Event Details", bold));
                        doc.add(infoRow("Date & Time", event.eventDate(), regular, bold));
                        doc.add(infoRow("Venue", event.venueName(), regular, bold));
                        doc.add(infoRow("Address", event.venueAddress(), regular, bold));

                        // ── Seat Table ───────────────────────────────────────────
                        doc.add(sectionHeading("Seat(s)", bold));

                        Table table = new Table(UnitValue.createPercentArray(new float[] { 2, 1, 1, 2, 1.5f }))
                                        .useAllAvailableWidth();
                        addTableHeader(table, bold, "Section", "Row", "Seat", "Tier", "Price");

                        int index = 0;
                        for (SeatDetail seat : event.seatDetails()) {
                                addTableRow(table, regular, index % 2 == 1,
                                                seat.sectionName(),
                                                seat.rowLabel(),
                                                String.valueOf(seat.seatNumber()),
                                                seat.tier(),
                                                "₹" + seat.price().toPlainString());
                                index++;
                        }
                        doc.add(table);

                        // ── Total ────────────────────────────────────────────────
                        BigDecimal total = event.totalAmount();
                        doc.add(new Paragraph()
                                        .add(new com.itextpdf.layout.element.Text("Total Paid: ").setFont(bold)
                                                        .setFontSize(11).setFontColor(TEXT_MUTED))
                                        .add(new com.itextpdf.layout.element.Text("₹" + total.toPlainString())
                                                        .setFont(bold).setFontSize(12).setFontColor(BRAND_CYAN))
                                        .setTextAlignment(TextAlignment.RIGHT)
                                        .setMarginTop(8));

                        // ── Booking Info ─────────────────────────────────────────
                        doc.add(sectionHeading("Booking Info", bold));
                        doc.add(infoRow("Booking ID", event.bookingId(), regular, bold));
                        doc.add(infoRow("Payment Ref", event.paymentId(), regular, bold));

                        // Divider
                        doc.add(new Paragraph("─────────────────────────────────────────")
                                        .setFontSize(8).setFontColor(LIGHT_BORDER)
                                        .setTextAlignment(TextAlignment.CENTER)
                                        .setMarginTop(8));

                        // ── Footer ───────────────────────────────────────────────
                        doc.add(new Paragraph("Present this scannable ticket at the venue gate for instant access.")
                                        .setFont(bold)
                                        .setFontSize(8)
                                        .setFontColor(BRAND_DARK_COLOR)
                                        .setTextAlignment(TextAlignment.CENTER)
                                        .setMarginTop(6));

                        doc.add(new Paragraph("For support: support@stagepass.app")
                                        .setFont(regular)
                                        .setFontSize(8)
                                        .setFontColor(TEXT_MUTED)
                                        .setTextAlignment(TextAlignment.CENTER));

                        doc.close();
                        log.info("PDF ticket generated for bookingId={}", event.bookingId());
                        return baos.toByteArray();

                } catch (IOException ex) {
                        log.error("PDF generation failed for bookingId={}: {}", event.bookingId(), ex.getMessage());
                        throw new NotificationException(
                                        "PDF ticket generation failed for bookingId=" + event.bookingId(), ex);
                }
        }

        // ── Helpers ──────────────────────────────────────────────────────────────

        private Paragraph sectionHeading(String text, PdfFont font) {
                return new Paragraph(text)
                                .setFont(font)
                                .setFontSize(10)
                                .setFontColor(BRAND_DARK_COLOR)
                                .setMarginTop(12)
                                .setMarginBottom(4);
        }

        private Paragraph infoRow(String label, String value, PdfFont regularFont, PdfFont boldFont) {
                return new Paragraph()
                                .add(new com.itextpdf.layout.element.Text(label + ": ").setFont(regularFont)
                                                .setFontSize(9).setFontColor(TEXT_MUTED))
                                .add(new com.itextpdf.layout.element.Text(value).setFont(boldFont).setFontSize(9)
                                                .setFontColor(TEXT_DARK))
                                .setMarginBottom(2);
        }

        private void addTableHeader(Table table, PdfFont bold, String... headers) {
                for (String h : headers) {
                        table.addHeaderCell(
                                        new Cell().add(new Paragraph(h).setFont(bold).setFontSize(8))
                                                        .setBackgroundColor(BRAND_DARK_COLOR)
                                                        .setFontColor(new DeviceRgb(255, 255, 255))
                                                        .setPadding(5)
                                                        .setBorder(new SolidBorder(new DeviceRgb(255, 255, 255),
                                                                        0.5f)));
                }
        }

        private void addTableRow(Table table, PdfFont font, boolean isOdd, String... values) {
                DeviceRgb bgColor = isOdd ? BG_TICKET_CARD : new DeviceRgb(255, 255, 255);
                for (String v : values) {
                        table.addCell(
                                        new Cell().add(new Paragraph(v).setFont(font).setFontSize(8)
                                                        .setFontColor(TEXT_DARK))
                                                        .setPadding(5)
                                                        .setBackgroundColor(bgColor)
                                                        .setBorder(new SolidBorder(LIGHT_BORDER, 0.5f)));
                }
        }
}