import SwiftUI
import UniformTypeIdentifiers
import SharedLogic

extension UTType {
    /// Declared in Info.plist, as iOS knows neither.
    static let gpx = UTType(importedAs: "com.topografix.gpx")
    static let fit = UTType(importedAs: "com.garmin.fit")
}

/// The routes on this iPhone, newest first: imported from GPX and FIT files, and started from here.
struct RoutesView: View {
    let courses: [Course]
    let actions: RouteActions
    /// Opens an outdoor workout of the kind, ready to start, following the route.
    let onStart: (WorkoutKind, Course) -> Void

    @State private var importing = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if courses.isEmpty {
                    ContentUnavailableView {
                        Label("No Routes", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                    } description: {
                        Text("Import a GPX or FIT file from Files, or open one in hashiru from Mail, Safari or another app.")
                    } actions: {
                        Button("Import Route") { importing = true }
                            .prominentButtonStyle()
                    }
                } else {
                    List {
                        ForEach(courses) { course in
                            NavigationLink {
                                RouteDetailView(course: course, actions: actions, onStart: onStart)
                            } label: {
                                RouteRow(course: course)
                            }
                        }
                        .onDelete { offsets in offsets.map { courses[$0] }.forEach(actions.delete) }
                    }
                }
            }
            .navigationTitle("Routes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("Import", systemImage: "square.and.arrow.down") { importing = true }
                }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.gpx, .fit], allowsMultipleSelection: true) { result in
                if case .success(let urls) = result { actions.importFiles(urls) }
            }
        }
    }
}

private struct RouteRow: View {
    let course: Course

    var body: some View {
        HStack(spacing: 14) {
            CourseThumbnail(course: course)
            VStack(alignment: .leading, spacing: 2) {
                Text(course.name)
                    .font(.headline)
                    .lineLimit(2)
                Text(course.summary)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One route: its map and size, and the workouts that follow it.
struct RouteDetailView: View {
    let course: Course
    let actions: RouteActions
    let onStart: (WorkoutKind, Course) -> Void

    @State private var confirmingDelete = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                RouteMap(course: course.coordinates, height: 320)
                WorkoutMetricsGrid(metrics: metrics)
                VStack(spacing: 12) {
                    startButton(.outdoorRun)
                    startButton(.outdoorWalk)
                }
                Button("Delete Route", role: .destructive) { confirmingDelete = true }
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.card, in: .rect(cornerRadius: 20))
            }
            .padding()
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(course.name)
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Delete this route?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete Route", role: .destructive) {
                actions.delete(course)
                dismiss()
            }
        }
    }

    private func startButton(_ kind: WorkoutKind) -> some View {
        Button {
            onStart(kind, course)
        } label: {
            Label(kind.title, systemImage: kind.symbol)
                .frame(maxWidth: .infinity)
        }
        .prominentButtonStyle()
    }

    private var metrics: [WorkoutMetric] {
        var metrics = [WorkoutMetric(title: "Distance", value: "\(kilometres(course.distanceMeters)) KM", color: .workoutDistance)]
        if let ascent = course.ascentMeters {
            metrics.append(WorkoutMetric(title: "Elevation Gain", value: "\(ascent.int32Value) M", color: .workoutSteps))
        }
        metrics.append(WorkoutMetric(title: "Shape", value: String(localized: course.isLoop ? "Loop" : "Point to Point"), color: .workoutVolt))
        return metrics
    }
}

extension Course {
    /// Distance, and the climb where the file had elevation.
    var summary: String {
        let distance = "\(kilometres(distanceMeters)) km"
        guard let ascent = ascentMeters else { return distance }
        return String(localized: "\(distance) · \(ascent.int32Value) m up")
    }
}
