package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import javax.swing.*;
import org.atlasalign.application.*;
import org.atlasalign.application.roi.*;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualRoiSelectionTest {
    @Test void multiSelectionSurvivesRefreshAndDeletesAsOneUndo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var basis=ReviewPluginFixtures.segmentedScaledAtlasBasis();
            var controller=new ReviewController(new AlignmentReviewSession(basis),
                    ReviewPluginFixtures.segmentedPreview(),ReviewPluginFixtures::plane,
                    () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(),basis.atlas()),
                    Runnable::run,Runnable::run);
            var panel=new SwingReviewPanel(controller);controller.attach(panel);
            var session=panel.manualRoiSessionForTests();
            String a=session.newPolygon("A",ReviewerRoiSide.LEFT,RoiPartOperation.ADD);
            session.addVertex(new Point2D(1,1));session.addVertex(new Point2D(10,1));session.addVertex(new Point2D(1,10));
            session.finishActivePart();String b=session.duplicate(a);session.duplicate(a);
            var before=session.snapshot().rois();
            JList<?> list=(JList<?>)find(panel,"manualRoiList");
            list.setSelectedIndices(new int[]{0,2});
            assertArrayEquals(new int[]{0,2},list.getSelectedIndices());
            controller.setCoronalLevel(controller.state().content().coronalLevel().zeroBasedAnteriorPosteriorIndex()+1);
            assertArrayEquals(new int[]{0,2},list.getSelectedIndices());
            ((JButton)find(panel,"manualRoiDeleteSelected")).doClick();
            assertEquals(java.util.List.of(b),session.snapshot().rois().stream().map(ReviewerRoi::id).toList());
            ((JButton)find(panel,"manualRoiUndo")).doClick();
            assertEquals(before,session.snapshot().rois());
        });
    }
    @Test void creatingRoiKeepsGuideControlsInView() throws Exception {
        final JScrollPane[] inspector = {null};
        SwingUtilities.invokeAndWait(() -> {
            var basis=ReviewPluginFixtures.segmentedScaledAtlasBasis();
            var controller=new ReviewController(new AlignmentReviewSession(basis),
                    ReviewPluginFixtures.segmentedPreview(),ReviewPluginFixtures::plane,
                    () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(),basis.atlas()),
                    Runnable::run,Runnable::run);
            var panel=new SwingReviewPanel(controller);controller.attach(panel);
            ((JButton)find(panel,"workflowStep4")).doClick();
            panel.setSize(1280,800);
            for(int i=0;i<5;i++) layout(panel);
            inspector[0]=(JScrollPane)find(panel,"workflowStage4Scroll");
            inspector[0].getViewport().setViewPosition(new java.awt.Point(0,0));
            var session=panel.manualRoiSessionForTests();
            session.newPolygon("New ROI",ReviewerRoiSide.LEFT,RoiPartOperation.ADD);
            session.addVertex(new Point2D(1,1));session.addVertex(new Point2D(10,1));session.addVertex(new Point2D(1,10));
            session.finishActivePart();
        });
        // Drain deferred caret visibility requests as well as model listeners.
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> assertEquals(0,inspector[0].getViewport().getViewPosition().y));
    }
    private static void layout(Container c) {
        c.invalidate();c.doLayout();
        for(Component child:c.getComponents()) if(child instanceof Container cc)layout(cc);
    }

    private static Component find(Container root,String name) {
        for(Component c:root.getComponents()) {
            if(name.equals(c.getName())) return c;
            if(c instanceof Container child) { Component f=find(child,name); if(f!=null)return f; }
        }
        return null;
    }
}
