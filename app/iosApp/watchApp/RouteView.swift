import MapKit
import SwiftUI

/// The walk's metrics, and with a route the map to follow it, a page each, turned with the crown.
struct WalkPages: View {
    @ObservedObject var model: WalkModel

    var body: some View {
        if let course = model.course {
            TabView {
                WalkView(model: model)
                RouteView(course: course, navigation: model.telemetry.navigation)
            }
            .tabViewStyle(.verticalPage)
        } else {
            WalkView(model: model)
        }
    }
}

/// The route as a grey line under where you are, with how far to go or how far off it you are.
struct RouteView: View {
    let course: WristCourse
    let navigation: WristNavigation?

    @State private var position = MapCameraPosition.userLocation(followsHeading: true, fallback: .automatic)

    var body: some View {
        Map(position: $position) {
            MapPolyline(coordinates: course.points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) })
                .stroke(Color.gray, style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round))
            UserAnnotation()
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .overlay(alignment: .bottom) {
            if let navigation {
                Label { Text(status(navigation)) } icon: { Image(systemName: symbol(navigation)) }
                    .font(.system(.footnote, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(navigation.onCourse ? AnyShapeStyle(.ultraThinMaterial) : AnyShapeStyle(Color.orange), in: .capsule)
                    .padding(.bottom, 4)
            }
        }
        .navigationTitle(course.name)
    }

    private func symbol(_ navigation: WristNavigation) -> String {
        if navigation.finished { return "flag.checkered" }
        if !navigation.joined { return "location.north.line" }
        return navigation.onCourse ? "point.topleft.down.to.point.bottomright.curvepath" : "exclamationmark.triangle.fill"
    }

    private func status(_ navigation: WristNavigation) -> String {
        if navigation.finished { return String(localized: "Finished") }
        if !navigation.joined || !navigation.onCourse { return String(localized: "\(navigation.offCourseMeters) m off") }
        let km = navigation.remainingMeters
        return String(localized: "\(String(format: "%d.%02d", km / 1000, km % 1000 / 10)) km to go")
    }
}
