import MapKit
import SwiftUI
import SharedLogic

extension RoutePoint {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}

extension CoursePoint {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lon) }
}

extension Course: @retroactive Identifiable {
    var coordinates: [CLLocationCoordinate2D] { points.map(\.coordinate) }
}

extension WorkoutKind {
    var outdoor: Bool { location == .outdoor }
}

/// A planned course as Fitness draws one to follow: a wide grey line, with flags where it starts and ends.
private struct CourseContent: MapContent {
    let course: [CLLocationCoordinate2D]

    var body: some MapContent {
        MapPolyline(coordinates: course)
            .stroke(Color.gray.opacity(0.75), style: StrokeStyle(lineWidth: 8, lineCap: .round, lineJoin: .round))
        if let first = course.first, let last = course.last {
            Annotation("Finish", coordinate: last, anchor: .bottom) {
                Image(systemName: "flag.checkered")
                    .font(.caption.bold())
                    .padding(5)
                    .background(.background, in: .circle)
            }
            .annotationTitles(.hidden)
            Annotation("Start", coordinate: first, anchor: .center) {
                Circle()
                    .fill(.white)
                    .stroke(.black, lineWidth: 2)
                    .frame(width: 12, height: 12)
            }
            .annotationTitles(.hidden)
        }
    }
}

/// The route as a volt line, as Fitness draws it, with where it began.
private struct RouteContent: MapContent {
    let route: [RoutePoint]

    var body: some MapContent {
        MapPolyline(coordinates: route.map(\.coordinate))
            .stroke(Color.workoutVoltFill, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
        if let first = route.first {
            Annotation("Start", coordinate: first.coordinate, anchor: .center) {
                Circle()
                    .fill(Color.workoutVoltFill)
                    .stroke(.black, lineWidth: 2)
                    .frame(width: 12, height: 12)
            }
            .annotationTitles(.hidden)
        }
    }
}

/// A finished workout's route or a planned course, fit to its bounds; for summaries and details.
struct RouteMap: View {
    var route: [RoutePoint] = []
    var course: [CLLocationCoordinate2D] = []
    var height: CGFloat = 240

    var body: some View {
        Map(initialPosition: .rect(mapBounds(route.map(\.coordinate) + course)), interactionModes: []) {
            CourseContent(course: course)
            RouteContent(route: route)
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .frame(height: height)
        .clipShape(.rect(cornerRadius: 20))
        .accessibilityLabel("Route map")
    }
}

/// The bounds of [coordinates] with a margin, never smaller than a street or so.
func mapBounds(_ coordinates: [CLLocationCoordinate2D]) -> MKMapRect {
    var coordinates = coordinates
    let rect = MKPolyline(coordinates: &coordinates, count: coordinates.count).boundingMapRect
    let minimum = 300 * MKMapPointsPerMeterAtLatitude(coordinates.first?.latitude ?? 0)
    let width = max(rect.width, minimum)
    let height = max(rect.height, minimum)
    return MKMapRect(x: rect.midX - width * 0.65, y: rect.midY - height * 0.65, width: width * 1.3, height: height * 1.3)
}

/// The live map during an outdoor workout: where you are, the course to follow and the route so far, following
/// you as you go.
struct LiveRouteMap: View {
    let route: [RoutePoint]
    var course: [CLLocationCoordinate2D] = []

    @State private var position = MapCameraPosition.userLocation(followsHeading: false, fallback: .automatic)

    var body: some View {
        Map(position: $position) {
            CourseContent(course: course)
            UserAnnotation()
            RouteContent(route: route)
        }
        .mapStyle(.standard(elevation: .realistic, pointsOfInterest: .excludingAll))
        .mapControls {
            MapUserLocationButton()
            MapCompass()
        }
    }
}

/// A course as a plain line in a small square, for lists, where a live map per row would be too much.
struct CourseThumbnail: View {
    let course: Course

    var body: some View {
        Canvas { context, size in
            let points = course.points
            guard points.count > 1 else { return }
            // Longitude shrinks towards the poles; scaled by the cosine, the shape keeps its proportions.
            let scale = cos(points[0].lat * .pi / 180)
            let xs = points.map { $0.lon * scale }
            let ys = points.map(\.lat)
            guard let minX = xs.min(), let maxX = xs.max(), let minY = ys.min(), let maxY = ys.max() else { return }
            let span = max(maxX - minX, maxY - minY, 1e-9)
            let inset = 6.0
            let side = min(size.width, size.height) - inset * 2
            func point(_ i: Int) -> CGPoint {
                CGPoint(
                    x: inset + (xs[i] - minX) / span * side + (side - (maxX - minX) / span * side) / 2,
                    y: inset + (maxY - ys[i]) / span * side + (side - (maxY - minY) / span * side) / 2
                )
            }
            var path = Path()
            path.move(to: point(0))
            for i in 1..<points.count { path.addLine(to: point(i)) }
            context.stroke(path, with: .color(.workoutVolt), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
        }
        .frame(width: 56, height: 56)
        .background(.fill.tertiary, in: .rect(cornerRadius: 12))
        .accessibilityHidden(true)
    }
}
