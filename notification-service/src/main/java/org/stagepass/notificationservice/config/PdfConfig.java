package org.stagepass.notificationservice.config;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PDF CONFIG
 *
 * Defines shared styling constants and font beans for TicketPdfService.
 * Centralising these here means TicketPdfService doesn't hardcode colours
 * or font references — easy to retheme the ticket without changing service logic.
 *
 * LIBRARY CHOICE:
 * Uses iText7 (com.itextpdf:itext7-core).
 * Alternative: OpenPDF (com.github.librepdf:openpdf) — Apache 2.0 licensed,
 * no commercial license needed. API is nearly identical to iText 5.
 * For a portfolio project, either works. For production, check iText7's AGPL license.
 *
 * PAGE SIZE:
 * A5 (148mm × 210mm) — feels like a physical ticket, smaller than A4.
 * Landscape A5 is even more ticket-like — adjust in TicketPdfService if preferred.
 *
 * FONTS:
 * StandardFonts.HELVETICA — built into every PDF viewer, no font file needed.
 * For a branded look, embed a custom .ttf font instead.
 */
@Configuration
public class PdfConfig {

    private static final Logger log = LoggerFactory.getLogger(PdfConfig.class);

    // ── BRAND COLOURS ─────────────────────────────────────────────────────────

    /** StagePass purple — used for header background and accent lines */
    public static final DeviceRgb BRAND_PURPLE = new DeviceRgb(83, 74, 183);

    /** Dark text on light background */
    public static final DeviceRgb TEXT_DARK = new DeviceRgb(30, 30, 30);

    /** Subtle secondary text */
    public static final DeviceRgb TEXT_SECONDARY = new DeviceRgb(100, 100, 100);

    /** White — for text on dark backgrounds */
    public static final DeviceRgb WHITE = new DeviceRgb(255, 255, 255);

    /** Light grey — for divider lines and table backgrounds */
    public static final DeviceRgb LIGHT_GREY = new DeviceRgb(240, 240, 240);

    // ── PAGE CONFIG ───────────────────────────────────────────────────────────

    /** A5 page — appropriate ticket size */
    public static final PageSize TICKET_PAGE_SIZE = PageSize.A5;

    /** Margin in points (1 point = 1/72 inch) */
    public static final float MARGIN = 20f;

    // ── FONT SIZES ────────────────────────────────────────────────────────────

    public static final float FONT_SIZE_HEADER    = 18f;
    public static final float FONT_SIZE_SUBHEADER = 13f;
    public static final float FONT_SIZE_BODY      = 10f;
    public static final float FONT_SIZE_SMALL     =  8f;

    // ── FONT BEANS ────────────────────────────────────────────────────────────

    /**
     * Standard Helvetica — no font file needed, embedded in PDF spec.
     * Used for regular body text.
     */
    @Bean(name = "pdfFontRegular")
    public PdfFont pdfFontRegular() {
        try {
            return PdfFontFactory.createFont(StandardFonts.HELVETICA);
        } catch (Exception e) {
            log.error("Failed to create PDF regular font: {}", e.getMessage());
            throw new IllegalStateException("PDF font initialisation failed", e);
        }
    }

    /**
     * Helvetica Bold — for headings and emphasis.
     */
    @Bean(name = "pdfFontBold")
    public PdfFont pdfFontBold() {
        try {
            return PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
        } catch (Exception e) {
            log.error("Failed to create PDF bold font: {}", e.getMessage());
            throw new IllegalStateException("PDF bold font initialisation failed", e);
        }
    }
}