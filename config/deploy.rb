# Example of how to use buildpacks in a Capistrano deployment to Ubuntu
 # You'll need a .env.deploy file in your root containing the production config

set :application, "pulse"
set :repository,  "git://github.com/heroku/pulse.git"
set :branch, "74129da3" # newer revisions declare too many merger processes
set :scm, :git

ssh_options[:forward_agent] = true

role :web, "vbox" # point this at your real host

set :deploy_via, :remote_cache

after("deploy:setup") do
  # for some reason capistrano doesn't set deploy_to permissions correctly
  run "sudo chown -R $USER #{deploy_to}"
  run "sudo apt-get update && sudo apt-get install git"
  run "sudo adduser pulse --disabled-password --gecos ''"
end

task :deps do
  run "sudo apt-get install -y git openjdk-6-jre-headless curl redis-server rubygems"
  run "sudo gem install --conservative foreman mason"
  run "mason buildpacks:install git://github.com/heroku/heroku-buildpack-clojure"
end

task :build do
  put File.read(".env.prod"), "#{deploy_to}/current/.env"
  run("mason build #{deploy_to}/current -o #{deploy_to}/out " +
      "-e #{deploy_to}/current/.env -c #{deploy_to}/cache")
  run("sudo foreman export upstart /etc/init -a pulse -d #{deploy_to}/out")
end

before :build, :deps

after :deploy, :build

namespace :deploy do # uses upstart
  task(:start) { run "sudo start pulse" }
  task(:stop) { run "sudo stop pulse" }
end
