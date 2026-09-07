package org.atlasalign.plugin.review;

/**
 * Headless-testable boundary implemented by the Swing panel.
 */
public interface ReviewView {

    void render(ReviewViewModel model);

    void showError(String title, String message);

    void reviewClosed();
}
